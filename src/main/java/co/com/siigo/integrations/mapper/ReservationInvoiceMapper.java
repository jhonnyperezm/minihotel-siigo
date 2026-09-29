package co.com.siigo.integrations.mapper;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.minihotel.BookingResponse;
import co.com.siigo.integrations.dto.minihotel.ReservationBalanceResponse;
import co.com.siigo.integrations.dto.minihotel.TransactionResponse;
import co.com.siigo.integrations.dto.siigo.CrearFacturaRequest;
import co.com.siigo.integrations.dto.siigo.FormaPagoResponse;
import co.com.siigo.integrations.dto.siigo.ImpuestoResponse;
import co.com.siigo.integrations.dto.siigo.ProductoResponse;
import co.com.siigo.integrations.service.ClienteService;
import co.com.siigo.integrations.service.HotelContextService;
import co.com.siigo.integrations.service.SiigoCatalogoService;
import co.com.siigo.integrations.util.Util;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Convierte una reservación de MiniHotel en una factura de venta de Siigo.
 *
 * <p>Reemplaza la conversión a {@code CrearDocumentoVentaRequest} de World Office.
 * Las reglas de negocio se conservan; cambia la forma de expresarlas:
 * <ul>
 *   <li>El renglón referencia el producto por <b>código</b>, no por id de inventario.</li>
 *   <li>El IVA se envía como un impuesto explícito en el renglón, con el precio unitario
 *       <b>sin</b> impuesto (MiniHotel entrega el valor con IVA incluido, así que se desagrega).</li>
 *   <li>La factura debe traer al menos un medio de pago cuyo valor iguale el total.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationInvoiceMapper {

    private static final String CASH_DEPARTMENT = "CASH";
    private static final String HORA_RESUMEN = "00:00";
    private static final int ESCALA = 2;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final SiigoCatalogoService catalogoService;
    private final HotelContextService hotelContextService;

    /**
     * Construye el cuerpo de la factura de venta.
     *
     * @param cliente           identificación y sucursal del tercero ya existente en Siigo
     * @param tipoComprobanteId id del comprobante FV con el que se numerará la factura
     * @param vendedorId        id del usuario vendedor
     * @param encabezado        reservación de MiniHotel
     * @param detalles          balance y transacciones de la reservación
     * @throws IllegalStateException si la reservación no tiene cargos facturables
     */
    public CrearFacturaRequest toFactura(ClienteService.ClienteRef cliente,
                                         Long tipoComprobanteId,
                                         Long vendedorId,
                                         BookingResponse encabezado,
                                         ReservationBalanceResponse detalles) {

        var facturacion = hotelContextService.getFacturacion();
        boolean esExtranjero = esGuestExtranjero(encabezado.getPrimaryGuest().getCountry());

        List<TransactionResponse> cargos = filtrarCargos(detalles.getTransactions());
        if (cargos.isEmpty()) {
            throw new IllegalStateException(
                    "Reservación " + encabezado.getMinihotelReservationId() +
                    " no tiene cargos facturables. Solo contiene transacciones de pago (CASH).");
        }

        // Huésped del exterior: servicio exento de IVA. Nacional: producto gravado.
        String codigoProducto = esExtranjero
                ? facturacion.getProductoExtranjero()
                : facturacion.getProductoNacional();

        ProductoResponse producto = catalogoService.obtenerProductoPorCodigo(codigoProducto);
        Optional<ImpuestoResponse> iva = esExtranjero ? Optional.empty() : catalogoService.obtenerImpuestoIva();
        Optional<Long> bodega = catalogoService.obtenerBodegaPara(producto);

        List<CrearFacturaRequest.Item> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (TransactionResponse cargo : cargos) {
            BigDecimal valorConImpuesto = valorSeguro(cargo.getAmount());
            BigDecimal porcentaje = iva.map(ImpuestoResponse::porcentajeSeguro).orElse(BigDecimal.ZERO);
            BigDecimal base = desagregarImpuesto(valorConImpuesto, porcentaje);

            items.add(new CrearFacturaRequest.Item(
                    producto.code(),
                    Util.truncar(descripcion(cargo, producto), 500),
                    BigDecimal.ONE,
                    base,
                    bodega.orElse(null),
                    null,
                    iva.map(i -> List.of(new CrearFacturaRequest.Impuesto(i.id()))).orElse(null)
            ));

            total = total.add(aplicarImpuesto(base, porcentaje));
        }

        String fechaDocumento = resolverFecha(encabezado, facturacion);
        FormaPagoResponse formaPago = resolverFormaPago(encabezado, facturacion);

        var pago = new CrearFacturaRequest.Pago(
                formaPago.id(),
                total,
                formaPago.manejaVencimiento() ? fechaDocumento : null
        );

        return new CrearFacturaRequest(
                new CrearFacturaRequest.Documento(tipoComprobanteId),
                null,                                   // consecutivo asignado por Siigo
                fechaDocumento,
                new CrearFacturaRequest.Cliente(cliente.identificacion(), cliente.sucursal()),
                catalogoService.obtenerCentroCostoId().orElse(null),
                resolverMoneda(encabezado, facturacion),
                vendedorId,
                observaciones(encabezado),
                items,
                List.of(pago),
                new CrearFacturaRequest.Sello(facturacion.isEnviarDian()),
                new CrearFacturaRequest.Correo(facturacion.isEnviarEmail())
        );
    }

    /**
     * Selecciona las transacciones facturables.
     *
     * <p>Las transacciones del departamento {@code CASH} son pagos, no cargos, y se excluyen.
     * Si quedan varias y alguna tiene hora {@code 00:00}, esa es el resumen final de la
     * reservación y se factura solo ella, para no duplicar el consumo.
     */
    private List<TransactionResponse> filtrarCargos(List<TransactionResponse> transactions) {
        if (transactions == null) return List.of();

        List<TransactionResponse> noCash = transactions.stream()
                .filter(t -> !esPagoEnEfectivo(t))
                .toList();

        if (noCash.size() > 1) {
            List<TransactionResponse> resumenes = noCash.stream()
                    .filter(t -> HORA_RESUMEN.equals(t.getTime()))
                    .toList();

            if (!resumenes.isEmpty()) {
                return List.of(resumenes.get(resumenes.size() - 1));
            }
        }
        return noCash;
    }

    private boolean esPagoEnEfectivo(TransactionResponse transaction) {
        return CASH_DEPARTMENT.equalsIgnoreCase(transaction.getDepartment());
    }

    /**
     * Determina el medio de pago según el {@code zip} de la reserva.
     *
     * <ul>
     *   <li>{@code zip} vacío o nulo → {@code forma-pago-contado} (Efectivo, código 1).</li>
     *   <li>{@code zip} con cualquier valor → {@code forma-pago-credito} (Pagos, código 6).</li>
     * </ul>
     * Si el hotel define {@code forma-pago-*-id}, se busca por id y el nombre se ignora.
     */
    private FormaPagoResponse resolverFormaPago(BookingResponse encabezado,
                                                IntegrationProperties.Facturacion facturacion) {

        boolean tieneZip = Optional.ofNullable(encabezado.getPrimaryGuest().getZip())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .isPresent();

        return tieneZip
                ? catalogoService.obtenerFormaPago(facturacion.getFormaPagoCreditoId(), facturacion.getFormaPagoCredito())
                : catalogoService.obtenerFormaPago(facturacion.getFormaPagoContadoId(), facturacion.getFormaPagoContado());
    }

    /**
     * Fecha del documento. Por defecto la de salida de la reservación.
     *
     * <p>La DIAN no acepta facturas electrónicas con fecha anterior a la actual; si se detecta
     * ese caso se registra una advertencia, y con {@code usar-fecha-actual=true} se emite con hoy.
     */
    private String resolverFecha(BookingResponse encabezado,
                                 IntegrationProperties.Facturacion facturacion) {

        String hoy = Util.hoyEnBogota().toString();
        if (facturacion.isUsarFechaActual()) {
            return hoy;
        }

        String fechaSalida = Util.formatearFecha(encabezado.getResGlobalInfo().getDeparture());

        if (facturacion.isEnviarDian() && LocalDate.parse(fechaSalida).isBefore(Util.hoyEnBogota())) {
            log.warn("La reservación {} tiene fecha de salida {} anterior a hoy. " +
                            "La DIAN puede rechazar la factura electrónica; considera usar-fecha-actual=true",
                    encabezado.getMinihotelReservationId(), fechaSalida);
        }
        return fechaSalida;
    }

    /**
     * Bloque de moneda extranjera. Solo se envía cuando la reserva no está en la moneda local,
     * y exige tener configurada moneda extranjera en Siigo Nube.
     */
    private CrearFacturaRequest.Moneda resolverMoneda(BookingResponse encabezado,
                                                      IntegrationProperties.Facturacion facturacion) {
        return null;

//        String moneda = encabezado.getResGlobalInfo().getCurrencyCode();
//        if (moneda == null || moneda.isBlank() || moneda.equalsIgnoreCase(facturacion.getMonedaLocal())) {
//            return null;
//        }
//
//        log.info("Reservación {} en moneda {}: se enviará con TRM {}",
//                encabezado.getMinihotelReservationId(), moneda, facturacion.getTrm());
//        return new CrearFacturaRequest.Moneda(moneda.toUpperCase(), facturacion.getTrm());
    }

    private String observaciones(BookingResponse encabezado) {
        var info = encabezado.getResGlobalInfo();
        return "Reserva MiniHotel " + encabezado.getMinihotelReservationId()
                + " | Huésped: " + encabezado.getPrimaryGuest().getFullName()
                + " | Estadía: " + info.getArrival() + " a " + info.getDeparture();
    }

    private String descripcion(TransactionResponse cargo, ProductoResponse producto) {
        String detalle = cargo.getDetails();
        return detalle == null || detalle.isBlank() ? producto.name() : detalle;
    }

    /**
     * Convierte un valor con impuesto incluido en su base gravable.
     * Con IVA del 19 %: {@code base = valor / 1.19}.
     */
    private BigDecimal desagregarImpuesto(BigDecimal valorConImpuesto, BigDecimal porcentaje) {
        if (porcentaje == null || porcentaje.compareTo(BigDecimal.ZERO) == 0) {
            return valorConImpuesto.setScale(ESCALA, RoundingMode.HALF_UP);
        }
        BigDecimal factor = BigDecimal.ONE.add(porcentaje.divide(CIEN, 6, RoundingMode.HALF_UP));
        return valorConImpuesto.divide(factor, ESCALA, RoundingMode.HALF_UP);
    }

    /**
     * Recompone el valor del renglón con impuesto, para que la suma de los medios de pago
     * coincida exactamente con el total que calculará Siigo.
     */
    private BigDecimal aplicarImpuesto(BigDecimal base, BigDecimal porcentaje) {
        if (porcentaje == null || porcentaje.compareTo(BigDecimal.ZERO) == 0) {
            return base.setScale(ESCALA, RoundingMode.HALF_UP);
        }
        BigDecimal factor = BigDecimal.ONE.add(porcentaje.divide(CIEN, 6, RoundingMode.HALF_UP));
        return base.multiply(factor).setScale(ESCALA, RoundingMode.HALF_UP);
    }

    private BigDecimal valorSeguro(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private boolean esGuestExtranjero(String country) {
        return country != null && !country.isBlank()
                && !country.equalsIgnoreCase("Colombia")
                && !country.equalsIgnoreCase("CO");
    }
}
