package co.com.siigo.integrations.service;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.webhook.WebhookEnvelope;
import co.com.siigo.integrations.dto.webhook.enums.DisparadorFacturacionEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reglas que deciden si un webhook genera factura.
 * Los JSON de ejemplo están tomados de la documentación de MiniHotel.
 */
class WebhookDisparadorServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private IntegrationProperties props;
    private WebhookDisparadorService service;

    @BeforeEach
    void setUp() {
        props = new IntegrationProperties();
        props.getWebhooks().setEnabled(true);
        props.getWebhooks().setFacturacionAutomatica(true);
        props.getWebhooks().setDisparador(DisparadorFacturacionEnum.OCUPACION);
        service = new WebhookDisparadorService(props, mapper);
    }

    // ─── disparador OCUPACION ─────────────────────────────────────────────

    @Test
    void ocupacionEnFalse_disparaFactura() {
        var decision = service.evaluar(envelope(OCUPACION_CHECKOUT));

        assertThat(decision.facturar()).isTrue();
        assertThat(decision.motivo()).contains("Check-out");
    }

    @Test
    void ocupacionEnTrue_noDisparaFactura() {
        var decision = service.evaluar(envelope(OCUPACION_CHECKIN));

        assertThat(decision.facturar()).isFalse();
        assertThat(decision.motivo()).contains("sin check-out");
    }

    @Test
    void ocupacionSinCampoOccupied_noDisparaFactura() {
        // Un occupied ausente no se interpreta como desocupada: ante la duda, no se factura.
        String json = """
                {"eventID":"e1","notificationID":10,"hotelCode":"autent93",
                 "notificationType":"room.occupancy.updated",
                 "payload":{"reservationNumber":"007004415","status":"IN",
                            "rooms":[{"roomNumber":"0101"}],"timestamp":"2026-02-03 00:03:29"}}""";

        assertThat(service.evaluar(envelope(json)).facturar()).isFalse();
    }

    @Test
    void reservacionOut_noDisparaSiElDisparadorEsOcupacion() {
        var decision = service.evaluar(envelope(RESERVA_OUT));

        assertThat(decision.facturar()).isFalse();
        assertThat(decision.motivo()).contains("OCUPACION");
    }

    // ─── disparador RESERVA_OUT ───────────────────────────────────────────

    @Test
    void reservacionOut_disparaCuandoEsElDisparadorConfigurado() {
        props.getWebhooks().setDisparador(DisparadorFacturacionEnum.RESERVA_OUT);

        var decision = service.evaluar(envelope(RESERVA_OUT));

        assertThat(decision.facturar()).isTrue();
        assertThat(decision.motivo()).contains("OUT");
    }

    @Test
    void reservacionEnOtroEstado_noDispara() {
        props.getWebhooks().setDisparador(DisparadorFacturacionEnum.RESERVA_OUT);
        String json = RESERVA_OUT.replace("\"OUT\"", "\"WL\"");

        var decision = service.evaluar(envelope(json));

        assertThat(decision.facturar()).isFalse();
        assertThat(decision.motivo()).contains("WL");
    }

    @Test
    void disparadorAmbos_aceptaLosDosEventos() {
        props.getWebhooks().setDisparador(DisparadorFacturacionEnum.AMBOS);

        assertThat(service.evaluar(envelope(OCUPACION_CHECKOUT)).facturar()).isTrue();
        assertThat(service.evaluar(envelope(RESERVA_OUT)).facturar()).isTrue();
    }

    // ─── eventos que nunca facturan ───────────────────────────────────────

    @Test
    void reservacionCreada_nuncaFactura() {
        props.getWebhooks().setDisparador(DisparadorFacturacionEnum.AMBOS);
        String json = RESERVA_OUT.replace("reservation.updated", "reservation.created");

        var decision = service.evaluar(envelope(json));

        assertThat(decision.facturar()).isFalse();
        assertThat(decision.motivo()).contains("aún no hay consumos");
    }

    @Test
    void reservacionCancelada_quedaParaRevisionManual() {
        props.getWebhooks().setDisparador(DisparadorFacturacionEnum.AMBOS);
        String json = """
                {"eventID":"e1","notificationID":207,"hotelCode":"autent93",
                 "notificationType":"reservation.cancelled",
                 "payload":{"reservationNumber":"007004731","source":"TELEPHONE",
                            "status":"CL","timestamp":"2026-06-10 14:10:00"}}""";

        var decision = service.evaluar(envelope(json));

        assertThat(decision.facturar()).isFalse();
        assertThat(decision.motivo()).contains("nota crédito");
    }

    @Test
    void tipoDesconocido_noRompeYQuedaRegistrado() {
        String json = OCUPACION_CHECKOUT.replace("room.occupancy.updated", "room.something.new");

        var decision = service.evaluar(envelope(json));

        assertThat(decision.facturar()).isFalse();
        assertThat(decision.motivo()).contains("desconocido");
    }

    @Test
    void sinNumeroDeReservacion_noFactura() {
        String json = """
                {"eventID":"e1","notificationID":11,"hotelCode":"autent93",
                 "notificationType":"room.occupancy.updated",
                 "payload":{"status":"OUT","rooms":[{"roomNumber":"0101","occupied":false}]}}""";

        var decision = service.evaluar(envelope(json));

        assertThat(decision.facturar()).isFalse();
        assertThat(decision.motivo()).contains("número de reservación");
    }

    // ─── interruptores de configuración ───────────────────────────────────

    @Test
    void facturacionAutomaticaDesactivada_soloRegistra() {
        props.getWebhooks().setFacturacionAutomatica(false);

        var decision = service.evaluar(envelope(OCUPACION_CHECKOUT));

        assertThat(decision.facturar()).isFalse();
        assertThat(decision.motivo()).contains("desactivada");
    }

    @Test
    void disparadorNinguno_soloRegistra() {
        props.getWebhooks().setDisparador(DisparadorFacturacionEnum.NINGUNO);

        assertThat(service.evaluar(envelope(OCUPACION_CHECKOUT)).facturar()).isFalse();
    }

    // ─── tolerancia al esquema ────────────────────────────────────────────

    @Test
    void aceptaEventIdEnMinuscula() {
        // La documentación usa eventID en los ejemplos y eventId en el esquema genérico.
        String json = OCUPACION_CHECKOUT.replace("\"eventID\"", "\"eventId\"");

        assertThat(envelope(json).eventId()).isEqualTo("56e39148-f14c-4135-a28e-3bae1489aa2a");
    }

    @Test
    void campoDesconocidoEnElPayload_noRompeElParseo() {
        String json = OCUPACION_CHECKOUT.replace(
                "\"status\":\"OUT\"", "\"status\":\"OUT\",\"campoNuevoDeMiniHotel\":123");

        assertThat(service.evaluar(envelope(json)).facturar()).isTrue();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private WebhookEnvelope envelope(String json) {
        try {
            return mapper.readValue(json, WebhookEnvelope.class);
        } catch (Exception e) {
            throw new IllegalStateException("JSON de prueba inválido", e);
        }
    }

    private static final String OCUPACION_CHECKOUT = """
            {"eventID":"56e39148-f14c-4135-a28e-3bae1489aa2a","notificationID":5,
             "hotelCode":"autent93","notificationType":"room.occupancy.updated",
             "payload":{"reservationNumber":"007004415","status":"OUT",
                        "rooms":[{"roomNumber":"0101","guestFirstName":"John","guestLastName":"Doe",
                                  "countryCode":"US","idNumber":"123456789",
                                  "checkInDate":"2026-02-01T15:00:00","checkOutDate":"2026-02-03T11:00:00",
                                  "occupied":false}],
                        "timestamp":"2026-02-03 00:05:22"}}""";

    private static final String OCUPACION_CHECKIN = """
            {"eventID":"56e39148-f14c-4135-a28e-3bae1489aa2a","notificationID":6,
             "hotelCode":"autent93","notificationType":"room.occupancy.updated",
             "payload":{"reservationNumber":"007004415","status":"IN",
                        "rooms":[{"roomNumber":"0101","occupied":true}],
                        "timestamp":"2026-02-03 00:03:29"}}""";

    private static final String RESERVA_OUT = """
            {"eventID":"597828f9-5870-473c-afe6-022771d72b66","notificationID":207,
             "hotelCode":"autent93","notificationType":"reservation.updated",
             "payload":{"reservationNumber":"007004731","source":"TELEPHONE",
                        "status":"OUT","timestamp":"2026-06-10 14:10:00"}}""";
}
