package co.com.siigo.integrations.mapper;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.minihotel.BookingResponse;
import co.com.siigo.integrations.dto.minihotel.GuestResponse;
import co.com.siigo.integrations.dto.minihotel.ResGlobalInfoResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationInvoiceMapperTest {

    private static final Long COMPROBANTE_ID = 22L;
    private static final Long VENDEDOR_ID = 629L;

    @Mock
    private SiigoCatalogoService catalogoService;

    @Mock
    private HotelContextService hotelContextService;

    @InjectMocks
    private ReservationInvoiceMapper mapper;

    private IntegrationProperties.Facturacion facturacion;
    private ClienteService.ClienteRef cliente;

    @BeforeEach
    void setUp() {
        facturacion = new IntegrationProperties.Facturacion();
        facturacion.setProductoNacional("ALOJAMIENTO");
        facturacion.setProductoExtranjero("ALOJAMIENTO-EXENTO");
        facturacion.setFormaPagoContado("Efectivo");
        facturacion.setFormaPagoCredito("Pagos");
        facturacion.setMonedaLocal("COP");
        facturacion.setTrm(BigDecimal.ONE);

        cliente = new ClienteService.ClienteRef("12345678", 0);

        when(hotelContextService.getFacturacion()).thenReturn(facturacion);
        when(catalogoService.obtenerProductoPorCodigo("ALOJAMIENTO")).thenReturn(productoGravado());
        when(catalogoService.obtenerProductoPorCodigo("ALOJAMIENTO-EXENTO")).thenReturn(productoExento());
        when(catalogoService.obtenerImpuestoIva()).thenReturn(Optional.of(iva19()));
        when(catalogoService.obtenerBodegaPara(any())).thenReturn(Optional.empty());
        when(catalogoService.obtenerCentroCostoId()).thenReturn(Optional.empty());
        when(catalogoService.obtenerFormaPago(any(), any())).thenReturn(credito());
    }

    // ─── estructura general ───────────────────────────────────────────────

    @Test
    void toFactura_armaElEncabezadoConComprobanteClienteYVendedor() {
        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("100000", "10:00")));

        assertThat(request.document().id()).isEqualTo(COMPROBANTE_ID);
        assertThat(request.seller()).isEqualTo(VENDEDOR_ID);
        assertThat(request.customer().identification()).isEqualTo("12345678");
        assertThat(request.customer().branchOffice()).isZero();
        assertThat(request.date()).isEqualTo("2026-03-01");
        assertThat(request.number()).isNull();     // el consecutivo lo asigna Siigo
    }

    @Test
    void toFactura_observacionesIncluyenReservaYHuesped() {
        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("100000", "10:00")));

        assertThat(request.observations())
                .contains("RES-001")
                .contains("Juan Perez");
    }

    // ─── IVA y desagregación de precios ───────────────────────────────────

    @Test
    void toFactura_huespedNacional_desagregaElIvaDelValorRecibido() {
        // MiniHotel entrega 119.000 con IVA incluido -> base 100.000
        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        CrearFacturaRequest.Item item = request.items().get(0);
        assertThat(item.code()).isEqualTo("ALOJAMIENTO");
        assertThat(item.price()).isEqualByComparingTo("100000.00");
        assertThat(item.quantity()).isEqualByComparingTo("1");
        assertThat(item.taxes()).hasSize(1);
        assertThat(item.taxes().get(0).id()).isEqualTo(13156L);
    }

    @Test
    void toFactura_huespedNacional_elPagoIgualaElTotalConImpuesto() {
        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.payments()).hasSize(1);
        assertThat(request.payments().get(0).value()).isEqualByComparingTo("119000.00");
    }

    @Test
    void toFactura_huespedExtranjero_usaProductoExentoYSinImpuestos() {
        var request = mapear(booking("RES-002", "Estados Unidos", null), balance(cargo("119000", "10:00")));

        CrearFacturaRequest.Item item = request.items().get(0);
        assertThat(item.code()).isEqualTo("ALOJAMIENTO-EXENTO");
        assertThat(item.taxes()).isNull();
        assertThat(item.price()).isEqualByComparingTo("119000.00");
        assertThat(request.payments().get(0).value()).isEqualByComparingTo("119000.00");
    }

    @Test
    void toFactura_sinIvaConfigurado_facturaElValorSinDesagregar() {
        when(catalogoService.obtenerImpuestoIva()).thenReturn(Optional.empty());

        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.items().get(0).price()).isEqualByComparingTo("119000.00");
        assertThat(request.items().get(0).taxes()).isNull();
    }

    // ─── selección de cargos ──────────────────────────────────────────────

    @Test
    void toFactura_excluyeLasTransaccionesCash() {
        var detalles = balance(cargo("119000", "10:00"), pagoCash("119000"));

        var request = mapear(booking("RES-001", "Colombia", null), detalles);

        assertThat(request.items()).hasSize(1);
        assertThat(request.items().get(0).price()).isEqualByComparingTo("100000.00");
    }

    @Test
    void toFactura_variosCargosConResumen_facturaSoloElResumen() {
        var detalles = balance(
                cargo("50000", "10:00"),
                cargo("30000", "14:00"),
                cargo("119000", "00:00"));

        var request = mapear(booking("RES-001", "Colombia", null), detalles);

        assertThat(request.items()).hasSize(1);
        assertThat(request.items().get(0).price()).isEqualByComparingTo("100000.00");
    }

    @Test
    void toFactura_variosCargosSinResumen_losFacturaTodos() {
        var detalles = balance(cargo("119000", "10:00"), cargo("59500", "14:00"));

        var request = mapear(booking("RES-001", "Colombia", null), detalles);

        assertThat(request.items()).hasSize(2);
        assertThat(request.payments().get(0).value()).isEqualByComparingTo("178500.00");
    }

    @Test
    void toFactura_soloTransaccionesCash_lanzaIllegalState() {
        var detalles = balance(pagoCash("119000"));

        assertThatThrownBy(() -> mapear(booking("RES-003", "Colombia", null), detalles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RES-003")
                .hasMessageContaining("cargos facturables");
    }

    // ─── forma de pago ────────────────────────────────────────────────────

    @Test
    void toFactura_conZip_usaPagos() {
        when(catalogoService.obtenerFormaPago(null, "Pagos")).thenReturn(
                new FormaPagoResponse(6L, "Pagos", "FV", true, false));

        var request = mapear(booking("RES-001", "Colombia", "Tarjeta"), balance(cargo("119000", "10:00")));

        assertThat(request.payments().get(0).id()).isEqualTo(6L);
    }

    @Test
    void toFactura_sinZip_usaEfectivoAunqueNoHayaCash() {
        when(catalogoService.obtenerFormaPago(null, "Efectivo")).thenReturn(
                new FormaPagoResponse(1L, "Efectivo", "FV", true, false));

        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.payments().get(0).id()).isEqualTo(1L);
    }

    @Test
    void toFactura_zipEnBlanco_usaEfectivo() {
        when(catalogoService.obtenerFormaPago(null, "Efectivo")).thenReturn(
                new FormaPagoResponse(1L, "Efectivo", "FV", true, false));

        var request = mapear(booking("RES-001", "Colombia", "   "), balance(cargo("119000", "10:00")));

        assertThat(request.payments().get(0).id()).isEqualTo(1L);
    }

    @Test
    void toFactura_conIdConfigurado_buscaPorId() {
        facturacion.setFormaPagoCreditoId(13586L);
        when(catalogoService.obtenerFormaPago(13586L, "Pagos")).thenReturn(
                new FormaPagoResponse(13586L, "Pagos renombrado", "FV", true, false));

        var request = mapear(booking("RES-001", "Colombia", "Tarjeta"), balance(cargo("119000", "10:00")));

        assertThat(request.payments().get(0).id()).isEqualTo(13586L);
    }

    @Test
    void toFactura_medioDePagoConVencimiento_enviaDueDate() {
        when(catalogoService.obtenerFormaPago(any(), any()))
                .thenReturn(new FormaPagoResponse(5636L, "Credito", "FV", true, true));

        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.payments().get(0).dueDate()).isEqualTo("2026-03-01");
    }

    @Test
    void toFactura_medioDePagoSinVencimiento_omiteDueDate() {
        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.payments().get(0).dueDate()).isNull();
    }

    // ─── moneda, fecha, timbrado ──────────────────────────────────────────

    @Test
    void toFactura_monedaLocal_noEnviaBloqueCurrency() {
        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.currency()).isNull();
    }

    @Test
    @Disabled("Moneda extranjera desactivada: resolverMoneda() siempre factura en la moneda local")
    void toFactura_monedaExtranjera_enviaCodigoYTrm() {
        facturacion.setTrm(new BigDecimal("3900"));
        BookingResponse booking = booking("RES-001", "Estados Unidos", null);
        booking.getResGlobalInfo().setCurrencyCode("USD");

        var request = mapear(booking, balance(cargo("100", "10:00")));

        assertThat(request.currency()).isNotNull();
        assertThat(request.currency().code()).isEqualTo("USD");
        assertThat(request.currency().exchangeRate()).isEqualByComparingTo("3900");
    }

    @Test
    void toFactura_usarFechaActual_ignoraLaFechaDeSalida() {
        facturacion.setUsarFechaActual(true);

        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.date()).isEqualTo(Util.hoyEnBogota().toString());
    }

    @Test
    void toFactura_reflejaLosFlagsDeTimbradoYCorreo() {
        facturacion.setEnviarDian(true);
        facturacion.setEnviarEmail(true);

        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.stamp().send()).isTrue();
        assertThat(request.mail().send()).isTrue();
    }

    @Test
    void toFactura_centroDeCostoConfigurado_seIncluye() {
        when(catalogoService.obtenerCentroCostoId()).thenReturn(Optional.of(235L));

        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.costCenter()).isEqualTo(235L);
    }

    @Test
    void toFactura_bodegaSoloSiElProductoManejaInventario() {
        when(catalogoService.obtenerBodegaPara(any())).thenReturn(Optional.of(15L));

        var request = mapear(booking("RES-001", "Colombia", null), balance(cargo("119000", "10:00")));

        assertThat(request.items().get(0).warehouse()).isEqualTo(15L);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private CrearFacturaRequest mapear(BookingResponse encabezado, ReservationBalanceResponse detalles) {
        return mapper.toFactura(cliente, COMPROBANTE_ID, VENDEDOR_ID, encabezado, detalles);
    }

    private BookingResponse booking(String reservationId, String country, String zip) {
        BookingResponse b = new BookingResponse();
        b.setMinihotelReservationId(reservationId);

        GuestResponse guest = new GuestResponse();
        guest.setGivenName("Juan");
        guest.setSurname("Perez");
        guest.setIdNumber("12345678");
        guest.setCountry(country);
        guest.setZip(zip);
        b.setPrimaryGuest(guest);

        ResGlobalInfoResponse info = new ResGlobalInfoResponse();
        info.setArrival("2026-02-27");
        info.setDeparture("2026-03-01");
        info.setCurrencyCode("COP");
        b.setResGlobalInfo(info);

        return b;
    }

    private ReservationBalanceResponse balance(TransactionResponse... transacciones) {
        ReservationBalanceResponse b = new ReservationBalanceResponse();
        b.setTransactions(List.of(transacciones));
        return b;
    }

    private TransactionResponse cargo(String monto, String hora) {
        TransactionResponse t = new TransactionResponse();
        t.setDepartment("ROOM");
        t.setTime(hora);
        t.setDetails("Alojamiento");
        t.setAmount(new BigDecimal(monto));
        t.setDebitCredit(1);
        return t;
    }

    private TransactionResponse pagoCash(String monto) {
        TransactionResponse t = new TransactionResponse();
        t.setDepartment("CASH");
        t.setTime("12:00");
        t.setDetails("Pago en efectivo");
        t.setAmount(new BigDecimal(monto));
        t.setDebitCredit(2);
        return t;
    }

    private ProductoResponse productoGravado() {
        return new ProductoResponse("p1", "ALOJAMIENTO", "Servicio de alojamiento", "Service",
                true, false, new ProductoResponse.Unidad("94", "Unidad"), List.of());
    }

    private ProductoResponse productoExento() {
        return new ProductoResponse("p2", "ALOJAMIENTO-EXENTO", "Alojamiento a no residentes", "Service",
                true, false, new ProductoResponse.Unidad("94", "Unidad"), List.of());
    }

    private ImpuestoResponse iva19() {
        return new ImpuestoResponse(13156L, "IVA 19%", "IVA", new BigDecimal("19"), true);
    }

    private FormaPagoResponse credito() {
        return new FormaPagoResponse(5636L, "Credito", "FV", true, false);
    }
}
