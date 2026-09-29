package co.com.siigo.integrations.service;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.webhook.WebhookEnvelope;
import co.com.siigo.integrations.entity.WebhookEvent;
import co.com.siigo.integrations.entity.WebhookEvent.EstadoWebhook;
import co.com.siigo.integrations.repository.WebhookEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Idempotencia y auditoría de los eventos recibidos.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookEventServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    private WebhookEventRepository repository;

    @Mock
    private HotelContextService hotelContextService;

    private WebhookEventService service;

    @BeforeEach
    void setUp() {
        IntegrationProperties props = new IntegrationProperties();
        service = new WebhookEventService(repository, hotelContextService, props);

        when(hotelContextService.findHotelByMinihotelId(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            WebhookEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            return e;
        });
    }

    // ─── registro e idempotencia ──────────────────────────────────────────

    @Test
    void registrar_eventoNuevo_seGuardaEnEstadoRecibido() {
        when(repository.findByHotelCodeAndNotificationId("autent93", 5L)).thenReturn(Optional.empty());

        var guardado = service.registrar(envelope(), JSON);

        assertThat(guardado).isPresent();
        WebhookEvent evento = capturarGuardado();
        assertThat(evento.getEstado()).isEqualTo(EstadoWebhook.RECIBIDO);
        assertThat(evento.getReservationNumber()).isEqualTo("007004415");
        assertThat(evento.getHotelCode()).isEqualTo("autent93");
        assertThat(evento.getNotificationId()).isEqualTo(5L);
        assertThat(evento.getIntentos()).isZero();
        assertThat(evento.getPayload()).contains("007004415");
    }

    @Test
    void registrar_notificacionRepetida_noVuelveAGuardar() {
        // MiniHotel reintenta hasta seis veces: el segundo envío no debe generar otra factura.
        when(repository.findByHotelCodeAndNotificationId("autent93", 5L))
                .thenReturn(Optional.of(WebhookEvent.builder().id(99L).build()));

        var resultado = service.registrar(envelope(), JSON);

        assertThat(resultado).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    void registrar_sinNotificationId_seGuardaIgualParaNoPerderElEvento() {
        String json = JSON.replace("\"notificationID\":5,", "");

        var resultado = service.registrar(envelope(json), json);

        assertThat(resultado).isPresent();
        verify(repository).save(any());
    }

    @Test
    void registrar_payloadEnorme_seRecortaAlLimiteConfigurado() {
        IntegrationProperties props = new IntegrationProperties();
        props.getWebhooks().setMaxPayloadChars(1000);
        service = new WebhookEventService(repository, hotelContextService, props);
        when(repository.findByHotelCodeAndNotificationId(any(), any())).thenReturn(Optional.empty());

        service.registrar(envelope(), "x".repeat(5000));

        assertThat(capturarGuardado().getPayload()).hasSize(1000);
    }

    // ─── transiciones de estado ───────────────────────────────────────────

    @Test
    void marcarProcesando_incrementaLosIntentos() {
        WebhookEvent evento = WebhookEvent.builder().id(1L).intentos(0).build();
        when(repository.findById(1L)).thenReturn(Optional.of(evento));

        service.marcarProcesando(1L);

        assertThat(evento.getEstado()).isEqualTo(EstadoWebhook.PROCESANDO);
        assertThat(evento.getIntentos()).isEqualTo(1);
    }

    @Test
    void marcarProcesado_enlazaLaTransaccionYLimpiaElError() {
        WebhookEvent evento = WebhookEvent.builder()
                .id(1L).intentos(1).errorMessage("fallo anterior").build();
        when(repository.findById(1L)).thenReturn(Optional.of(evento));

        service.marcarProcesado(1L, 42L, "Factura Siigo FV-2-22");

        assertThat(evento.getEstado()).isEqualTo(EstadoWebhook.PROCESADO);
        assertThat(evento.getSyncTransactionId()).isEqualTo(42L);
        assertThat(evento.getErrorMessage()).isNull();
        assertThat(evento.getProcesadoEn()).isNotNull();
    }

    @Test
    void marcarIgnorado_conservaElMotivo() {
        WebhookEvent evento = WebhookEvent.builder().id(1L).intentos(0).build();
        when(repository.findById(1L)).thenReturn(Optional.of(evento));

        service.marcarIgnorado(1L, "Reservación creada: aún no hay consumos que facturar");

        assertThat(evento.getEstado()).isEqualTo(EstadoWebhook.IGNORADO);
        assertThat(evento.getMotivo()).contains("consumos");
    }

    @Test
    void marcarFallido_recortaMensajesLargos() {
        WebhookEvent evento = WebhookEvent.builder().id(1L).intentos(1).build();
        when(repository.findById(1L)).thenReturn(Optional.of(evento));

        service.marcarFallido(1L, "e".repeat(5000));

        assertThat(evento.getEstado()).isEqualTo(EstadoWebhook.FALLIDO);
        assertThat(evento.getErrorMessage()).hasSize(2000);
    }

    @Test
    void reencolar_devuelveElEventoAEstadoRecibido() {
        WebhookEvent evento = WebhookEvent.builder()
                .id(1L).intentos(2).estado(EstadoWebhook.FALLIDO).errorMessage("error").build();
        when(repository.findById(1L)).thenReturn(Optional.of(evento));

        service.reencolar(1L);

        assertThat(evento.getEstado()).isEqualTo(EstadoWebhook.RECIBIDO);
        assertThat(evento.getErrorMessage()).isNull();
        assertThat(evento.getIntentos()).isEqualTo(2);   // el historial de intentos se conserva
    }

    @Test
    void reencolar_eventoInexistente_lanzaIllegalArgument() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reencolar(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    void marcarProcesado_eventoInexistente_noRompe() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        service.marcarProcesado(99L, 1L, "ok");

        verify(repository, never()).save(any());
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private WebhookEvent capturarGuardado() {
        ArgumentCaptor<WebhookEvent> captor = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private WebhookEnvelope envelope() {
        return envelope(JSON);
    }

    private WebhookEnvelope envelope(String json) {
        try {
            return mapper.readValue(json, WebhookEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException("JSON de prueba inválido", e);
        }
    }

    private static final String JSON = """
            {"eventID":"56e39148-f14c-4135-a28e-3bae1489aa2a","notificationID":5,
             "hotelCode":"autent93","notificationType":"room.occupancy.updated",
             "payload":{"reservationNumber":"007004415","status":"OUT",
                        "rooms":[{"roomNumber":"0101","occupied":false}],
                        "timestamp":"2026-02-03 00:05:22"}}""";
}
