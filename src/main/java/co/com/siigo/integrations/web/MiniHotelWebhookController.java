package co.com.siigo.integrations.web;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.webhook.WebhookEnvelope;
import co.com.siigo.integrations.dto.webhook.WebhookEventResponse;
import co.com.siigo.integrations.entity.WebhookEvent;
import co.com.siigo.integrations.service.HotelContextService;
import co.com.siigo.integrations.service.WebhookDisparadorService;
import co.com.siigo.integrations.service.WebhookEventService;
import co.com.siigo.integrations.service.WebhookProcessingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoint que recibe las notificaciones de MiniHotel.
 *
 * <p><b>Contrato con MiniHotel:</b> hay que solicitarles el alta indicando esta URL y unas
 * credenciales de Basic Auth. Ellos consideran entregada la notificación con cualquier
 * respuesta 2xx y fallida si no la reciben en 15 segundos, reintentando después a los
 * 10 s, 1 min, 5 min, 10 min, 1 h y 6 h.
 *
 * <p><b>Política de códigos de respuesta:</b>
 * <ul>
 *   <li><b>200</b> en cuanto el evento queda guardado, incluso si luego no se puede facturar.
 *       Reintentar la entrega no arreglaría un problema de facturación, y sí generaría
 *       eventos duplicados.</li>
 *   <li><b>200</b> también para eventos duplicados y para los que no disparan factura:
 *       fueron recibidos correctamente.</li>
 *   <li><b>400</b> si el cuerpo no es interpretable. Es un error permanente; reintentarlo
 *       no cambia nada.</li>
 *   <li><b>503</b> si el módulo está deshabilitado o si falla el guardado. Aquí sí interesa
 *       que MiniHotel reintente, porque el problema es temporal y del lado de esta app.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/minihotel")
@RequiredArgsConstructor
public class MiniHotelWebhookController {

    private final WebhookEventService webhookEventService;
    private final WebhookDisparadorService disparadorService;
    private final WebhookProcessingService processingService;
    private final HotelContextService hotelContextService;
    private final IntegrationProperties props;
    private final ObjectMapper objectMapper;

    /**
     * Recibe una notificación de MiniHotel.
     *
     * <p>El método hace lo mínimo indispensable antes de responder: deserializar, deduplicar,
     * guardar y encolar. Todo lo costoso ocurre en {@link WebhookProcessingService}.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> recibir(@RequestBody JsonNode body) {

        if (!props.getWebhooks().isEnabled()) {
            log.warn("[WEBHOOK] Notificación recibida con el módulo deshabilitado");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El módulo de webhooks está deshabilitado");
        }

        WebhookEnvelope envelope;
        try {
            envelope = objectMapper.treeToValue(body, WebhookEnvelope.class);
        } catch (Exception ex) {
            log.warn("[WEBHOOK] Cuerpo no interpretable: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El cuerpo de la notificación no tiene el formato esperado");
        }

        if (envelope.notificationType() == null || envelope.notificationType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el campo notificationType");
        }

        Optional<WebhookEvent> registrado = guardar(envelope, body);

        if (registrado.isEmpty()) {
            // Reintento de una notificación ya recibida: se confirma sin volver a facturar.
            return ResponseEntity.ok(Map.<String, Object>of(
                    "received", true,
                    "duplicate", true,
                    "message", "Notificación ya registrada previamente"));
        }

        Long eventoId = registrado.get().getId();
        var decision = disparadorService.evaluar(envelope);

        if (!decision.facturar()) {
            webhookEventService.marcarIgnorado(eventoId, decision.motivo());
            log.info("[WEBHOOK] {} · no dispara factura: {}", envelope.describir(), decision.motivo());
            return ResponseEntity.ok(Map.<String, Object>of(
                    "received", true,
                    "eventId", eventoId,
                    "queued", false,
                    "message", decision.motivo()));
        }

        // A partir de aquí el trabajo es asíncrono: MiniHotel recibe su 200 de inmediato.
        processingService.procesarAsync(eventoId);

        log.info("[WEBHOOK] {} · encolado para facturación: {}", envelope.describir(), decision.motivo());
        return ResponseEntity.ok(Map.<String, Object>of(
                "received", true,
                "eventId", eventoId,
                "queued", true,
                "message", decision.motivo()));
    }

    /**
     * Prueba de conectividad para verificar credenciales y ruta sin generar eventos.
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(Map.<String, Object>of(
                "status", "ok",
                "enabled", props.getWebhooks().isEnabled(),
                "disparador", props.getWebhooks().getDisparador().name(),
                "facturacionAutomatica", props.getWebhooks().isFacturacionAutomatica(),
                "hotelesRegistrados", hotelContextService.getAllHotels().stream()
                        .map(h -> h.getMinihotelAuth() == null ? "?" : h.getMinihotelAuth().getHotelId())
                        .toList()));
    }

    // ==================== CONSULTA Y REPROCESO (panel) ====================

    @GetMapping("/events")
    public ResponseEntity<List<WebhookEventResponse>> listar(
            @RequestParam(required = false) String estado,
            @RequestParam(required = false) String reservationNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta
    ) {
        if (reservationNumber != null && !reservationNumber.isBlank()) {
            return ResponseEntity.ok(webhookEventService.findByReservationNumber(reservationNumber));
        }
        if (estado != null && !estado.isBlank()) {
            return ResponseEntity.ok(webhookEventService.findByEstado(estado));
        }
        if (desde != null && hasta != null) {
            return ResponseEntity.ok(webhookEventService.findByRango(desde, hasta));
        }
        return ResponseEntity.ok(webhookEventService.findAll());
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<WebhookEventResponse> detalle(@PathVariable Long id) {
        return webhookEventService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Reprocesa un evento a mano. Sirve tanto para los que fallaron como para los que se
     * ignoraron por configuración y se quieren facturar de todas formas.
     *
     * <p>Se ejecuta de forma síncrona: quien lo dispara desde el panel espera el resultado.
     */
    @PostMapping("/events/{id}/reprocesar")
    public ResponseEntity<WebhookEventResponse> reprocesar(@PathVariable Long id) {
        webhookEventService.reencolar(id);
        processingService.procesar(id);

        return webhookEventService.findById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Evento de webhook no encontrado: " + id));
    }

    /** Elimina los eventos que superaron el período de retención. */
    @PostMapping("/events/limpiar")
    public ResponseEntity<Map<String, Object>> limpiar() {
        int eliminados = webhookEventService.limpiarAntiguos();
        return ResponseEntity.ok(Map.<String, Object>of(
                "eliminados", eliminados,
                "retenerDias", processingService.getRetenerDias()));
    }

    /**
     * Guarda el evento. Si el guardado falla se responde 503 para que MiniHotel reintente:
     * es el único caso en el que un reintento sí puede resolver el problema.
     */
    private Optional<WebhookEvent> guardar(WebhookEnvelope envelope, JsonNode body) {
        try {
            return webhookEventService.registrar(envelope, body.toString());
        } catch (Exception ex) {
            log.error("[WEBHOOK] No se pudo registrar la notificación {}: {}",
                    envelope.describir(), ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo registrar la notificación; reintente más tarde");
        }
    }
}
