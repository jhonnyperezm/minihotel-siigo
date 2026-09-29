package co.com.siigo.integrations.service;

import co.com.siigo.integrations.config.IntegrationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HotelContextServiceTest {

    @Mock
    private IntegrationProperties properties;

    @InjectMocks
    private HotelContextService service;

    private IntegrationProperties.HotelConfig hotelA;
    private IntegrationProperties.HotelConfig hotelB;

    @BeforeEach
    void setUp() {
        hotelA = new IntegrationProperties.HotelConfig();
        hotelA.setHotelKey("hotel-a");
        hotelA.setHotelName("Hotel A");
        hotelA.setEnabled(true);
        var authA = new IntegrationProperties.HotelConfig.MiniHotelAuth();
        authA.setSiigoTenant("SUC-A");
        authA.setHotelId("H001");
        authA.setUsername("userA");
        authA.setPassword("passA");
        hotelA.setMinihotelAuth(authA);

        hotelB = new IntegrationProperties.HotelConfig();
        hotelB.setHotelKey("hotel-b");
        hotelB.setHotelName("Hotel B");
        hotelB.setEnabled(false);
        var authB = new IntegrationProperties.HotelConfig.MiniHotelAuth();
        authB.setSiigoTenant("SUC-B");
        authB.setHotelId("H002");
        authB.setUsername("userB");
        authB.setPassword("passB");
        hotelB.setMinihotelAuth(authB);
    }

    // ─── setCurrentHotel ──────────────────────────────────────────────────

    @Test
    void setCurrentHotel_existingKey_returnsTrueAndSetsContext() {
        when(properties.getHotels()).thenReturn(List.of(hotelA, hotelB));

        boolean result = service.setCurrentHotel("hotel-a");

        assertThat(result).isTrue();
        assertThat(service.getCurrentHotel().getHotelKey()).isEqualTo("hotel-a");
    }

    @Test
    void setCurrentHotel_nonExistingKey_returnsFalse() {
        when(properties.getHotels()).thenReturn(List.of(hotelA));

        boolean result = service.setCurrentHotel("no-existe");

        assertThat(result).isFalse();
    }

    // ─── getCurrentHotel ──────────────────────────────────────────────────

    @Test
    void getCurrentHotel_withoutContext_throwsIllegalState() {
        assertThatThrownBy(() -> service.getCurrentHotel())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("setCurrentHotel");
    }

    // ─── clearCurrentHotel ────────────────────────────────────────────────

    @Test
    void clearCurrentHotel_removesContext() {
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        service.clearCurrentHotel();

        assertThat(service.hasCurrentHotel()).isFalse();
    }

    // ─── hasCurrentHotel ──────────────────────────────────────────────────

    @Test
    void hasCurrentHotel_withoutContext_returnsFalse() {
        assertThat(service.hasCurrentHotel()).isFalse();
    }

    @Test
    void hasCurrentHotel_withContext_returnsTrue() {
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        assertThat(service.hasCurrentHotel()).isTrue();
    }

    // ─── findHotelByKey ───────────────────────────────────────────────────

    @Test
    void findHotelByKey_existing_returnsHotel() {
        when(properties.getHotels()).thenReturn(List.of(hotelA, hotelB));

        var result = service.findHotelByKey("hotel-b");

        assertThat(result).isPresent();
        assertThat(result.get().getHotelName()).isEqualTo("Hotel B");
    }

    @Test
    void findHotelByKey_notFound_returnsEmpty() {
        when(properties.getHotels()).thenReturn(List.of(hotelA));

        assertThat(service.findHotelByKey("phantom")).isEmpty();
    }

    // ─── findHotelByTenant ──────────────────────────────────────────

    @Test
    void findHotelByTenant_existing_returnsHotel() {
        when(properties.getHotels()).thenReturn(List.of(hotelA, hotelB));

        var result = service.findHotelByTenant("SUC-A");

        assertThat(result).isPresent();
        assertThat(result.get().getHotelKey()).isEqualTo("hotel-a");
    }

    @Test
    void findHotelByTenant_caseInsensitive() {
        when(properties.getHotels()).thenReturn(List.of(hotelA));

        assertThat(service.findHotelByTenant("suc-a")).isPresent();
    }

    @Test
    void findHotelByTenant_notFound_returnsEmpty() {
        when(properties.getHotels()).thenReturn(List.of(hotelA));

        assertThat(service.findHotelByTenant("SUC-Z")).isEmpty();
    }

    // ─── setCurrentHotelByTenant ────────────────────────────────────────

    @Test
    void setCurrentHotelByTenant_existing_returnsTrueAndSetsContext() {
        when(properties.getHotels()).thenReturn(List.of(hotelA, hotelB));

        boolean result = service.setCurrentHotelByTenant("SUC-B");

        assertThat(result).isTrue();
        assertThat(service.getCurrentHotel().getHotelKey()).isEqualTo("hotel-b");
    }

    @Test
    void setCurrentHotelByTenant_notFound_returnsFalse() {
        when(properties.getHotels()).thenReturn(List.of(hotelA));

        assertThat(service.setCurrentHotelByTenant("SUC-Z")).isFalse();
    }

    // ─── getAllHotels / getActiveHotels ───────────────────────────────────

    @Test
    void getAllHotels_returnsBothHotels() {
        when(properties.getHotels()).thenReturn(List.of(hotelA, hotelB));

        assertThat(service.getAllHotels()).hasSize(2);
    }

    @Test
    void getActiveHotels_returnsOnlyEnabledHotels() {
        when(properties.getHotels()).thenReturn(List.of(hotelA, hotelB));

        var active = service.getActiveHotels();

        assertThat(active).hasSize(1);
        assertThat(active.get(0).getHotelKey()).isEqualTo("hotel-a");
    }

    // ─── getTenantKey / getFacturacion / getCredencialesSiigo ─────────────

    @Test
    void getTenantKey_sinContexto_devuelveDefault() {
        assertThat(service.getTenantKey()).isEqualTo(HotelContextService.TENANT_POR_DEFECTO);
    }

    @Test
    void getTenantKey_conContexto_devuelveElTenantDelHotel() {
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        assertThat(service.getTenantKey()).isEqualTo("SUC-A");
    }

    @Test
    void getTenantKey_sinTenantDefinido_caeEnElHotelKey() {
        hotelA.getMinihotelAuth().setSiigoTenant(null);
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        assertThat(service.getTenantKey()).isEqualTo("hotel-a");
    }

    @Test
    void getFacturacion_hotelSinConfig_usaLaGlobal() {
        var siigo = new IntegrationProperties.Siigo();
        siigo.getFacturacion().setProductoNacional("GLOBAL");
        when(properties.getSiigo()).thenReturn(siigo);
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        assertThat(service.getFacturacion().getProductoNacional()).isEqualTo("GLOBAL");
    }

    @Test
    void getFacturacion_hotelConConfig_tienePrioridad() {
        when(properties.getSiigo()).thenReturn(new IntegrationProperties.Siigo());
        var propia = new IntegrationProperties.FacturacionHotel();
        propia.setProductoNacional("PROPIO");
        hotelA.setFacturacion(propia);
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        assertThat(service.getFacturacion().getProductoNacional()).isEqualTo("PROPIO");
    }

    @Test
    void deberiaHeredarLaGlobal_cuandoElHotelNoDefineElParametro() {
        var siigo = new IntegrationProperties.Siigo();
        siigo.getFacturacion().setUsarFechaActual(true);
        siigo.getFacturacion().setImpuestoIva("IVA GLOBAL");
        when(properties.getSiigo()).thenReturn(siigo);
        var propia = new IntegrationProperties.FacturacionHotel();
        propia.setCiudadPorDefecto("Envigado");
        hotelA.setFacturacion(propia);
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        var facturacion = service.getFacturacion();

        assertThat(facturacion.getCiudadPorDefecto()).isEqualTo("Envigado");
        assertThat(facturacion.isUsarFechaActual()).isTrue();
        assertThat(facturacion.getImpuestoIva()).isEqualTo("IVA GLOBAL");
    }

    @Test
    void deberiaRespetarElFalseDelHotel_cuandoLaGlobalEsTrue() {
        var siigo = new IntegrationProperties.Siigo();
        siigo.getFacturacion().setEnviarDian(true);
        when(properties.getSiigo()).thenReturn(siigo);
        var propia = new IntegrationProperties.FacturacionHotel();
        propia.setEnviarDian(false);
        hotelA.setFacturacion(propia);
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        assertThat(service.getFacturacion().isEnviarDian()).isFalse();
    }

    @Test
    void deberiaHeredarLaGlobal_cuandoElHotelDejaElTextoVacio() {
        var siigo = new IntegrationProperties.Siigo();
        siigo.getFacturacion().setTipoComprobanteCodigo("FV-1");
        when(properties.getSiigo()).thenReturn(siigo);
        var propia = new IntegrationProperties.FacturacionHotel();
        propia.setTipoComprobanteCodigo("");
        hotelA.setFacturacion(propia);
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        assertThat(service.getFacturacion().getTipoComprobanteCodigo()).isEqualTo("FV-1");
    }

    @Test
    void getCredencialesSiigo_hotelConCredenciales_lasUsa() {
        var credenciales = new IntegrationProperties.Credenciales();
        credenciales.setUsername("user-a");
        credenciales.setAccessKey("key-a");
        credenciales.setPartnerId("App");
        hotelA.setSiigoAuth(credenciales);
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        assertThat(service.getCredencialesSiigo().getUsername()).isEqualTo("user-a");
    }

    @Test
    void getCredencialesSiigo_sinCredenciales_lanzaIllegalState() {
        when(properties.getSiigo()).thenReturn(new IntegrationProperties.Siigo());
        when(properties.getHotels()).thenReturn(List.of(hotelA));
        service.setCurrentHotel("hotel-a");

        assertThatThrownBy(() -> service.getCredencialesSiigo())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credenciales de Siigo");
    }
}
