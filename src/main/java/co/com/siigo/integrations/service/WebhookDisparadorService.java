package co.com.siigo.integrations.service;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.webhook.ReservationEventPayload;
import co.com.siigo.integrations.dto.webhook.RoomOccupancyPayload;
import co.com.siigo.integrations.dto.webhook.WebhookEnvelope;
import co.com.siigo.integrations.dto.webhook.enums.WebhookEventTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Decide si un evento recibido debe generar una factura.
 *
 * <p>Está separado del resto para que la regla sea fácil de leer, de cambiar y de probar:
 * es la única pieza que traduce "qué pasó en el hotel" a "hay que facturar".
 *
 * <p>El disparador por defecto es {@code room.occupancy.updated} con {@code occupied = false},
 * porque MiniHotel advierte que el estado de la reservación no siempre refleja el estado real
 * de la habitación y recomienda apoyarse en {@code occupied}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDisparadorService {

    private final IntegrationProperties props;
    private final ObjectMapper objectMapper;

    /**
     * Evalúa el evento contra la configuración vigente.
     *
     * @param envelope sobre recibido
     * @return la decisión, con el motivo siempre diligenciado para que quede en la auditoría
     */
    public Decision evaluar(WebhookEnvelope envelope) {
        var config = props.getWebhooks();

        if (!config.isFacturacionAutomatica()) {
            return Decision.no("Facturación automática desactivada: el evento solo se registra");
        }
        if (!config.getDisparador().incluyeOcupacion() && !config.getDisparador().incluyeReservaOut()) {
            return Decision.no("Disparador configurado en NINGUNO: el evento solo se registra");
        }

        WebhookEventTypeEnum tipo = WebhookEventTypeEnum.fromTipo(envelope.notificationType());
        if (tipo == null) {
            return Decision.no("Tipo de notificación desconocido para esta versión: "
                    + envelope.notificationType());
        }

        if (envelope.reservationNumber() == null || envelope.reservationNumber().isBlank()) {
            return Decision.no("El payload no trae número de reservación");
        }

        return switch (tipo) {
            case OCUPACION_ACTUALIZADA -> evaluarOcupacion(envelope, config);
            case RESERVACION_ACTUALIZADA -> evaluarReservacion(envelope, config);
            case RESERVACION_CANCELADA -> Decision.no("Reservación cancelada: requiere revisión manual "
                    + "(una factura ya emitida se anula con nota crédito, no automáticamente)");
            case RESERVACION_CREADA -> Decision.no("Reservación creada: aún no hay consumos que facturar");
        };
    }

    /**
     * {@code room.occupancy.updated} dispara la factura cuando alguna habitación queda
     * desocupada, que es el check-out efectivo.
     */
    private Decision evaluarOcupacion(WebhookEnvelope envelope, IntegrationProperties.Webhooks config) {
        if (!config.getDisparador().incluyeOcupacion()) {
            return Decision.no("El disparador configurado es " + config.getDisparador()
                    + ": los eventos de ocupación no facturan");
        }

        RoomOccupancyPayload payload = leer(envelope, RoomOccupancyPayload.class);
        if (payload == null) {
            return Decision.no("No se pudo interpretar el payload de ocupación");
        }

        if (!payload.hayDesocupacion()) {
            return Decision.no("Cambio de ocupación sin check-out (ninguna habitación con occupied = false)");
        }

        return Decision.si("Check-out detectado en "
                + payload.roomsOrEmpty().stream()
                        .filter(RoomOccupancyPayload.Room::estaDesocupada)
                        .map(RoomOccupancyPayload.Room::roomNumber)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("habitación sin número"));
    }

    /**
     * {@code reservation.updated} dispara la factura cuando la reservación pasa a estado OUT.
     */
    private Decision evaluarReservacion(WebhookEnvelope envelope, IntegrationProperties.Webhooks config) {
        if (!config.getDisparador().incluyeReservaOut()) {
            return Decision.no("El disparador configurado es " + config.getDisparador()
                    + ": las modificaciones de reservación no facturan");
        }

        ReservationEventPayload payload = leer(envelope, ReservationEventPayload.class);
        if (payload == null) {
            return Decision.no("No se pudo interpretar el payload de reservación");
        }

        if (payload.estaCancelada()) {
            return Decision.no("Reservación cancelada: requiere revisión manual");
        }
        if (!payload.tieneSalida()) {
            return Decision.no("Reservación modificada con estado '" + payload.status()
                    + "'; solo se factura con estado OUT");
        }

        return Decision.si("Reservación en estado OUT");
    }

    /**
     * Deserializa el nodo del payload al tipo concreto, tolerando esquemas incompletos.
     *
     * @return el payload, o {@code null} si el JSON no encaja
     */
    private <T> T leer(WebhookEnvelope envelope, Class<T> tipo) {
        try {
            if (envelope.payload() == null) return null;
            return objectMapper.treeToValue(envelope.payload(), tipo);
        } catch (Exception ex) {
            log.warn("[WEBHOOK] No se pudo interpretar el payload de {}: {}",
                    envelope.describir(), ex.getMessage());
            return null;
        }
    }

    /**
     * Resultado de la evaluación.
     *
     * @param facturar si el evento debe generar una factura
     * @param motivo   explicación legible, que se guarda en la auditoría en ambos casos
     */
    public record Decision(boolean facturar, String motivo) {

        static Decision si(String motivo) {
            return new Decision(true, motivo);
        }

        static Decision no(String motivo) {
            return new Decision(false, motivo);
        }
    }
}
