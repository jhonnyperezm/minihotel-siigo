package co.com.siigo.integrations.dto.webhook;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sobre genérico de todas las notificaciones de MiniHotel.
 *
 * <p>El {@code payload} se deja como {@link JsonNode} a propósito: cada
 * {@code notificationType} trae una estructura distinta y conviene guardar el JSON crudo
 * en la auditoría antes de intentar interpretarlo. Así, un cambio de esquema por parte de
 * MiniHotel no hace que se pierda el evento.
 *
 * <p><b>Sobre el nombre del identificador:</b> la documentación de MiniHotel usa
 * {@code eventID} en los ejemplos y {@code eventId} en el esquema genérico, y pide
 * explícitamente que el parseo tolere ambas variantes. De ahí el {@link JsonAlias}.
 *
 * <p><b>Sobre la deduplicación:</b> aunque la documentación describe {@code eventId} como
 * un GUID único por evento, en sus propios ejemplos dos notificaciones distintas
 * ({@code reservation.updated} y {@code reservation.cancelled}) comparten el mismo GUID con
 * distinto {@code notificationID}. Por eso la llave de deduplicación de esta integración es
 * {@code hotelCode + notificationID}, no el GUID.
 *
 * @param eventId          GUID del evento
 * @param notificationId   identificador interno incremental de la notificación
 * @param hotelCode        código de propiedad de MiniHotel; es el enrutador multi-tenant
 * @param notificationType tipo de evento (p. ej. {@code room.occupancy.updated})
 * @param payload          datos del evento, sin interpretar
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookEnvelope(
        @JsonProperty("eventID") @JsonAlias({"eventId", "eventid"}) String eventId,
        @JsonProperty("notificationID") @JsonAlias({"notificationId", "notificationid"}) Long notificationId,
        String hotelCode,
        String notificationType,
        JsonNode payload
) {

    /**
     * Número de reservación, leído directamente del nodo del payload.
     * Todos los tipos de evento actuales lo incluyen en la raíz.
     *
     * @return el número de reservación, o {@code null} si el payload no lo trae
     */
    public String reservationNumber() {
        if (payload == null) return null;
        JsonNode nodo = payload.get("reservationNumber");
        return nodo == null || nodo.isNull() ? null : nodo.asText();
    }

    /** Estado de la reservación reportado en el payload, cuando existe. */
    public String estadoReserva() {
        if (payload == null) return null;
        JsonNode nodo = payload.get("status");
        return nodo == null || nodo.isNull() ? null : nodo.asText();
    }

    /** Descripción corta para logs: tipo, hotel y reservación. */
    public String describir() {
        return notificationType + " · hotel " + hotelCode + " · reserva " + reservationNumber()
                + " · notificacion " + notificationId;
    }
}
