package co.com.siigo.integrations.service;

import co.com.siigo.integrations.client.SiigoCatalogoClient;
import co.com.siigo.integrations.dto.siigo.FormaPagoResponse;
import co.com.siigo.integrations.dto.siigo.enums.DocumentoTipoEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SiigoCatalogoServiceTest {

    @Mock private SiigoCatalogoClient catalogoClient;
    @Mock private HotelContextService hotelContextService;

    @InjectMocks private SiigoCatalogoService service;

    @BeforeEach
    void setUp() {
        when(hotelContextService.getTenantKey()).thenReturn("tenant-test");
        when(catalogoClient.listarFormasPago(DocumentoTipoEnum.FACTURA_VENTA)).thenReturn(List.of(
                new FormaPagoResponse(7027L, "Efectivo", "FV", true, false),
                new FormaPagoResponse(13586L, "Pagos Davivienda", "FV", true, false)));
    }

    // ─── forma de pago ────────────────────────────────────────────────────

    @Test
    void obtenerFormaPago_conId_ignoraElNombre() {
        var formaPago = service.obtenerFormaPago(13586L, "Pagos");

        assertThat(formaPago.id()).isEqualTo(13586L);
    }

    @Test
    void obtenerFormaPago_idInexistente_fallaEnVezDeUsarOtroMedio() {
        assertThatThrownBy(() -> service.obtenerFormaPago(999L, "Efectivo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("999");
    }

    @Test
    void obtenerFormaPago_sinId_buscaPorNombre() {
        var formaPago = service.obtenerFormaPago(null, "efectivo");

        assertThat(formaPago.id()).isEqualTo(7027L);
    }
}
