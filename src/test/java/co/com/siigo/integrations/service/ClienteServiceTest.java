package co.com.siigo.integrations.service;

import co.com.siigo.integrations.client.SiigoClienteClient;
import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.minihotel.GuestResponse;
import co.com.siigo.integrations.dto.siigo.ClienteResponse;
import co.com.siigo.integrations.dto.siigo.CrearClienteRequest;
import co.com.siigo.integrations.dto.siigo.enums.TipoIdentificacionEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClienteServiceTest {

    @Mock
    private SiigoClienteClient clienteClient;

    @Mock
    private HotelContextService hotelContextService;

    @InjectMocks
    private ClienteService service;

    private IntegrationProperties.Facturacion facturacion;

    @BeforeEach
    void setUp() {
        facturacion = new IntegrationProperties.Facturacion();
        facturacion.setConsumidorFinalIdentificacion("222222222222");
        facturacion.setSucursalCliente(0);
        facturacion.setCiudadPorDefecto("Medellín");

        when(hotelContextService.getFacturacion()).thenReturn(facturacion);
    }

    // ─── consumidor final ─────────────────────────────────────────────────

    @Test
    void obtenerOCrearCliente_sinIdentificacion_usaConsumidorFinal() {
        when(clienteClient.buscarPorIdentificacion("222222222222"))
                .thenReturn(Optional.of(cliente("222222222222")));

        var ref = service.obtenerOCrearCliente(guest(null, "Colombia"));

        assertThat(ref.identificacion()).isEqualTo("222222222222");
        verify(clienteClient, never()).crearCliente(any());
    }

    @Test
    void obtenerOCrearCliente_consumidorFinalInexistente_lanzaIllegalState() {
        when(clienteClient.buscarPorIdentificacion("222222222222")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtenerOCrearCliente(guest("  ", "Colombia")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("consumidor final");
    }

    @Test
    void obtenerOCrearCliente_identificacionDeRelleno_usaConsumidorFinal() {
        when(clienteClient.buscarPorIdentificacion("222222222222"))
                .thenReturn(Optional.of(cliente("222222222222")));

        for (String relleno : List.of("1", "0", "123", "000000", "1111111111", "1.111.111")) {
            var ref = service.obtenerOCrearCliente(guest(relleno, "Colombia"));
            assertThat(ref.identificacion()).as(relleno).isEqualTo("222222222222");
        }
        verify(clienteClient, never()).crearCliente(any());
    }

    @Test
    void esIdentificacionValida_documentosReales_sonValidos() {
        assertThat(service.esIdentificacionValida("12345678")).isTrue();
        assertThat(service.esIdentificacionValida("800197268")).isTrue();
        assertThat(service.esIdentificacionValida("BE189406")).isTrue();
    }

    // ─── cliente existente vs nuevo ───────────────────────────────────────

    @Test
    void obtenerOCrearCliente_yaExiste_noVuelveACrearlo() {
        when(clienteClient.buscarPorIdentificacion("12345678"))
                .thenReturn(Optional.of(cliente("12345678")));

        var ref = service.obtenerOCrearCliente(guest("12345678", "Colombia"));

        assertThat(ref.identificacion()).isEqualTo("12345678");
        verify(clienteClient, never()).crearCliente(any());
    }

    @Test
    void obtenerOCrearCliente_noExiste_loCreaComoPersonaNatural() {
        when(clienteClient.buscarPorIdentificacion("12345678")).thenReturn(Optional.empty());
        when(clienteClient.crearCliente(any())).thenReturn(cliente("12345678"));

        service.obtenerOCrearCliente(guest("12345678", "Colombia"));

        CrearClienteRequest request = capturarRequest();
        assertThat(request.personType()).isEqualTo("Person");
        assertThat(request.idType()).isEqualTo("13");
        assertThat(request.identification()).isEqualTo("12345678");
        assertThat(request.name()).containsExactly("Juan", "Perez");
        assertThat(request.type()).isEqualTo("Customer");
    }

    @Test
    void obtenerOCrearCliente_creaConResponsabilidadFiscalNoAplica() {
        when(clienteClient.buscarPorIdentificacion(any())).thenReturn(Optional.empty());
        when(clienteClient.crearCliente(any())).thenReturn(cliente("12345678"));

        service.obtenerOCrearCliente(guest("12345678", "Colombia"));

        assertThat(capturarRequest().fiscalResponsibilities())
                .extracting(CrearClienteRequest.ResponsabilidadFiscal::code)
                .containsExactly("R-99-PN");
    }

    @Test
    void obtenerOCrearCliente_siigoNoDevuelveElTercero_lanzaIllegalState() {
        when(clienteClient.buscarPorIdentificacion(any())).thenReturn(Optional.empty());
        when(clienteClient.crearCliente(any())).thenReturn(null);

        assertThatThrownBy(() -> service.obtenerOCrearCliente(guest("12345678", "Colombia")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no devolvió el tercero");
    }

    // ─── dirección y ciudad ───────────────────────────────────────────────

    @Test
    void obtenerOCrearCliente_ciudadReconocida_usaSusCodigosDane() {
        when(clienteClient.buscarPorIdentificacion(any())).thenReturn(Optional.empty());
        when(clienteClient.crearCliente(any())).thenReturn(cliente("12345678"));

        GuestResponse guest = guest("12345678", "Colombia");
        guest.setCity("Envigado");
        service.obtenerOCrearCliente(guest);

        var ciudad = capturarRequest().address().city();
        assertThat(ciudad.countryCode()).isEqualTo("CO");
        assertThat(ciudad.cityCode()).isEqualTo("05266");
    }

    @Test
    void obtenerOCrearCliente_ciudadDesconocida_usaLaPorDefecto() {
        when(clienteClient.buscarPorIdentificacion(any())).thenReturn(Optional.empty());
        when(clienteClient.crearCliente(any())).thenReturn(cliente("12345678"));

        GuestResponse guest = guest("12345678", "Colombia");
        guest.setCity("Ciudad Inventada");
        service.obtenerOCrearCliente(guest);

        assertThat(capturarRequest().address().city().cityCode()).isEqualTo("05001"); // Medellín
    }

    @Test
    void obtenerOCrearCliente_sinDireccion_registraNoInformada() {
        when(clienteClient.buscarPorIdentificacion(any())).thenReturn(Optional.empty());
        when(clienteClient.crearCliente(any())).thenReturn(cliente("12345678"));

        service.obtenerOCrearCliente(guest("12345678", "Colombia"));

        assertThat(capturarRequest().address().address()).isEqualTo("No Informada");
    }

    @Test
    void obtenerOCrearCliente_correoInvalido_usaCorreoPorDefecto() {
        when(clienteClient.buscarPorIdentificacion(any())).thenReturn(Optional.empty());
        when(clienteClient.crearCliente(any())).thenReturn(cliente("12345678"));

        GuestResponse guest = guest("12345678", "Colombia");
        guest.setEmail("no es un correo");
        service.obtenerOCrearCliente(guest);

        assertThat(capturarRequest().contacts().get(0).email()).isEqualTo("correo@noinformado.com");
    }

    @Test
    void obtenerOCrearCliente_telefonoConSeparadores_seNormaliza() {
        when(clienteClient.buscarPorIdentificacion(any())).thenReturn(Optional.empty());
        when(clienteClient.crearCliente(any())).thenReturn(cliente("12345678"));

        GuestResponse guest = guest("12345678", "Colombia");
        guest.setPhone("+57 300 600 3344");
        service.obtenerOCrearCliente(guest);

        assertThat(capturarRequest().phones().get(0).number()).isEqualTo("5730060033");
    }

    // ─── NIT ──────────────────────────────────────────────────────────────

    @Test
    void obtenerOCrearCliente_nit_seCreaComoEmpresaConDigitoVerificacion() {
        when(clienteClient.buscarPorIdentificacion("800197268")).thenReturn(Optional.empty());
        when(clienteClient.crearCliente(any())).thenReturn(cliente("800197268"));

        service.obtenerOCrearCliente(guest("800197268-4", "Colombia"));

        CrearClienteRequest request = capturarRequest();
        assertThat(request.idType()).isEqualTo("31");
        assertThat(request.personType()).isEqualTo("Company");
        assertThat(request.identification()).isEqualTo("800197268");
        assertThat(request.checkDigit()).isEqualTo("4");
        assertThat(request.name()).hasSize(1);
        assertThat(request.vatResponsible()).isTrue();
    }

    // ─── inferencia del tipo de documento ─────────────────────────────────

    @Test
    void inferirTipoIdentificacion_numeroSimpleColombiano_esCedula() {
        assertThat(service.inferirTipoIdentificacion("12345678", "Colombia"))
                .isEqualTo(TipoIdentificacionEnum.CEDULA_CIUDADANIA);
    }

    @Test
    void inferirTipoIdentificacion_conLetras_esPasaporte() {
        assertThat(service.inferirTipoIdentificacion("BE189406", "Colombia"))
                .isEqualTo(TipoIdentificacionEnum.PASAPORTE);
    }

    @Test
    void inferirTipoIdentificacion_extranjeroConSoloDigitos_esCedulaExtranjeria() {
        assertThat(service.inferirTipoIdentificacion("987654321", "Argentina"))
                .isEqualTo(TipoIdentificacionEnum.CEDULA_EXTRANJERIA);
    }

    @Test
    void inferirTipoIdentificacion_formatoNit_esNit() {
        assertThat(service.inferirTipoIdentificacion("900123456-7", "Colombia"))
                .isEqualTo(TipoIdentificacionEnum.NIT);
        assertThat(service.inferirTipoIdentificacion("900.123.456-7", "CO"))
                .isEqualTo(TipoIdentificacionEnum.NIT);
    }

    @Test
    void inferirTipoIdentificacion_numeroDemasiadoLargo_degradaADocumentoExtranjero() {
        assertThat(service.inferirTipoIdentificacion("12345678901234567", "Colombia"))
                .isEqualTo(TipoIdentificacionEnum.DOCUMENTO_EXTRANJERO);
    }

    @Test
    void inferirTipoIdentificacion_vacio_esCedula() {
        assertThat(service.inferirTipoIdentificacion(null, "Colombia"))
                .isEqualTo(TipoIdentificacionEnum.CEDULA_CIUDADANIA);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private CrearClienteRequest capturarRequest() {
        ArgumentCaptor<CrearClienteRequest> captor = ArgumentCaptor.forClass(CrearClienteRequest.class);
        verify(clienteClient).crearCliente(captor.capture());
        return captor.getValue();
    }

    private GuestResponse guest(String idNumber, String country) {
        GuestResponse guest = new GuestResponse();
        guest.setGivenName("Juan");
        guest.setSurname("Perez");
        guest.setIdNumber(idNumber);
        guest.setCountry(country);
        guest.setEmail("juan.perez@example.com");
        return guest;
    }

    private ClienteResponse cliente(String identificacion) {
        return new ClienteResponse("guid-1", "Customer", "Person",
                new ClienteResponse.TipoIdentificacion("13", "Cédula de ciudadanía"),
                identificacion, 0, null, List.of("Juan", "Perez"), null, true);
    }
}
