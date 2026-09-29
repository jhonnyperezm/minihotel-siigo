package co.com.siigo.integrations.service;

import co.com.siigo.integrations.client.MiniHotelClient;
import co.com.siigo.integrations.client.SiigoFacturaClient;
import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.minihotel.BookingResponse;
import co.com.siigo.integrations.dto.minihotel.ReservationBalanceResponse;
import co.com.siigo.integrations.dto.minihotel.enums.ReservationExcludedEnum;
import co.com.siigo.integrations.dto.siigo.CrearFacturaRequest;
import co.com.siigo.integrations.dto.siigo.FacturaResponse;
import co.com.siigo.integrations.entity.SyncTransaction;
import co.com.siigo.integrations.mapper.ReservationInvoiceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static co.com.siigo.integrations.util.Util.serializeToJson;
import static java.util.Objects.nonNull;
import static java.util.Optional.ofNullable;

/**
 * Servicio principal de integración entre MiniHotel y Siigo.
 *
 * <p>Coordina el ciclo completo: obtener reservaciones de MiniHotel, resolver el tercero,
 * mapear la factura, enviarla a Siigo y registrar el resultado en la auditoría.
 *
 * <p>Frente a la versión de World Office desaparece un paso: no hay que contabilizar el
 * documento después de crearlo, porque Siigo lo contabiliza al guardarlo. En su lugar,
 * cuando el comprobante es electrónico y la configuración lo pide, el documento se timbra
 * ante la DIAN durante la misma creación ({@code stamp.send=true}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final MiniHotelClient miniHotelClient;
    private final ClienteService clienteService;
    private final SiigoCatalogoService catalogoService;
    private final ReservationInvoiceMapper mapper;
    private final SiigoFacturaClient facturaClient;
    private final SyncTransactionService syncTransactionService;
    private final HotelContextService hotelContextService;
    private final IntegrationProperties props;

    /**
     * Sincroniza las reservaciones con salida dentro de un rango de fechas.
     *
     * @param fromDate fecha inicial (yyyy-MM-dd)
     * @param toDate   fecha final (yyyy-MM-dd)
     * @return facturas creadas en Siigo
     */
    public List<FacturaResponse> syncFacturasASiigo(String fromDate, String toDate) {
        try {
            var encabezados = miniHotelClient.getReservationsByDepartureDate(fromDate, toDate);
            return procesarReservas(encabezados, null, true);
        } catch (Exception e) {
            log.error("Error crítico al obtener reservas: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Sincroniza una reservación puntual.
     *
     * @param reservationNumber identificador de la reservación en MiniHotel
     */
    public List<FacturaResponse> syncFacturasASiigo(String reservationNumber) {
        return syncFacturasASiigo(reservationNumber, (Long) null);
    }

    /**
     * Sincroniza una reservación puntual, opcionalmente reutilizando una transacción existente
     * en lugar de crear una nueva. Es el camino que usa el reintento desde el panel.
     *
     * @param reservationNumber     identificador de la reservación en MiniHotel
     * @param existingTransactionId transacción a reiniciar a PENDING, o {@code null} para crear una
     * @throws IllegalArgumentException si la reservación no existe en MiniHotel
     */
    public List<FacturaResponse> syncFacturasASiigo(String reservationNumber, Long existingTransactionId) {
        try {
            var encabezado = miniHotelClient.getReservationByMinihotelId(reservationNumber)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Reserva no encontrada en MiniHotel: " + reservationNumber));
            return procesarReservas(List.of(encabezado), existingTransactionId, false);
        } catch (Exception e) {
            log.error("Error crítico al obtener reservas: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Procesa una lista de reservaciones. Para cada una:
     * <ol>
     *   <li>Descarta las excluidas por canal de venta o marca de pago</li>
     *   <li>Registra la transacción en estado PENDING</li>
     *   <li>Obtiene el balance de cargos desde MiniHotel</li>
     *   <li>Busca o crea el tercero en Siigo</li>
     *   <li>Mapea la factura y la envía</li>
     *   <li>Marca la transacción como SUCCESS o FAILED</li>
     * </ol>
     *
     * <p>Un fallo en una reservación no detiene el lote: se registra y se continúa.
     *
     * @param omitirRegistradas si es {@code true}, las reservas que ya tienen transacción se
     *                          saltan en silencio. Lo usan las sincronizaciones por rango, que
     *                          repiten el mismo rango varias veces al día; la sincronización de
     *                          una reserva puntual lo deja en {@code false} para informar que ya existe.
     */
    private List<FacturaResponse> procesarReservas(List<BookingResponse> encabezados, Long existingTransactionId,
                                                   boolean omitirRegistradas) {
        List<FacturaResponse> respuestas = new ArrayList<>();
        ContextoSiigo contexto = getContextoSiigo();

        try {
            log.info("Total de reservas a procesar: {}", encabezados.size());

            for (BookingResponse booking : encabezados) {
                SyncTransaction transaction = null;
                CrearFacturaRequest request = null;

                // Reservas que no se facturan desde aquí (Airbnb, Expedia, marcadas como efectivo)
                if (ReservationExcludedEnum.isSourceExcluded(booking.getSource())
                        || ReservationExcludedEnum.isZipExcluded(booking.getPrimaryGuest().getZip())) {
                    continue;
                }

                if (omitirRegistradas && syncTransactionService.existeTransaccion(
                        booking.getMinihotelReservationId(), contexto.minihotelId())) {
                    log.debug("Reserva {} ya registrada, se omite", booking.getMinihotelReservationId());
                    continue;
                }

                try {
                    log.info("Procesando reserva: {} - Huésped: {}", booking.getMinihotelReservationId(),
                            booking.getPrimaryGuest().getFullName());

                    transaction = ofNullable(existingTransactionId)
                            .map(syncTransactionService::resetToPending)
                            .orElseGet(() -> syncTransactionService.createPendingTransaction(
                                    booking.getMinihotelReservationId(), contexto, null));

                    ReservationBalanceResponse detalles = miniHotelClient.getReservationBalance(
                            booking.getMinihotelReservationId());

                    var cliente = clienteService.obtenerOCrearCliente(booking.getPrimaryGuest());

                    request = mapper.toFactura(cliente, contexto.tipoComprobanteId(), contexto.vendedorId(),
                            booking, detalles);

                    final SyncTransaction finalTransaction = transaction;

                    facturaClient.crearFactura(request)
                            .ifPresent(factura -> {
                                syncTransactionService.markAsSuccess(finalTransaction.getId(), factura);
                                registrarEstadoDian(booking.getMinihotelReservationId(), factura);

                                log.info("Sincronización exitosa para la reserva {}. Factura Siigo: {}",
                                        booking.getMinihotelReservationId(), factura.numeroDocumento());

                                respuestas.add(factura);
                            });

                } catch (Exception e) {
                    if (nonNull(transaction)) {
                        syncTransactionService.markAsFailed(transaction.getId(), e.getMessage(),
                                serializeToJson(request, new ObjectMapper()));
                    }
                    log.error("Error al sincronizar la reserva {}: {}",
                            booking.getMinihotelReservationId(), e.getMessage(), e);
                }
            }

            log.info("Proceso completado. Exitosas: {} de {}", respuestas.size(), encabezados.size());
            return respuestas;

        } catch (Exception e) {
            log.error("Error crítico al procesar reservas: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Deja constancia del resultado del timbrado. Si el comprobante es electrónico y la DIAN
     * lo rechazó, se consulta el detalle del error para poder diagnosticarlo desde el panel.
     */
    private void registrarEstadoDian(String reservationNumber, FacturaResponse factura) {
        if (factura.stamp() == null || factura.stamp().status() == null) return;

        if (factura.aceptadaPorDian()) {
            log.info("Factura {} aceptada por la DIAN. CUFE: {}",
                    factura.numeroDocumento(), factura.stamp().cufe());
            return;
        }

        log.warn("Factura {} de la reserva {} quedó en estado DIAN '{}'",
                factura.numeroDocumento(), reservationNumber, factura.stamp().status());
    }

    /**
     * Resuelve el contexto de facturación del hotel activo en el hilo.
     *
     * <p>En World Office esto significaba encontrar {@code empresaId} y {@code prefijoId}.
     * En Siigo equivale al tipo de comprobante y al vendedor, ambos propios del tenant.
     */
    ContextoSiigo getContextoSiigo() {
        String siigoTenant = hotelContextService.getTenantKey();
        String minihotelId = hotelContextService.getCurrentHotel().getMinihotelAuth().getHotelId();

        Long tipoComprobanteId = catalogoService.obtenerTipoComprobanteId();
        Long vendedorId = catalogoService.obtenerVendedorId();

        log.debug("Contexto Siigo: tenant={}, comprobante={}, vendedor={}, hotelMiniHotel={}",
                siigoTenant, tipoComprobanteId, vendedorId, minihotelId);

        return new ContextoSiigo(tipoComprobanteId, vendedorId, siigoTenant, minihotelId);
    }

    /**
     * Datos del tenant de Siigo con los que se emite la factura.
     *
     * @param tipoComprobanteId id del comprobante FV (equivalente al prefijo de World Office)
     * @param vendedorId        id del usuario vendedor
     * @param siigoTenant       clave del tenant, guardada en la auditoría para poder reintentar
     * @param minihotelId       identificador del hotel en MiniHotel
     */
    public record ContextoSiigo(Long tipoComprobanteId, Long vendedorId, String siigoTenant, String minihotelId) {
    }
}
