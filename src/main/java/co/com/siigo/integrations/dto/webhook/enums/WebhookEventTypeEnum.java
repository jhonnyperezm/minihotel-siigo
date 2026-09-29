package co.com.siigo.integrations.dto.webhook.enums;

import lombok.Getter;

/**
 * Tipos de notificación que MiniHotel envía por webhook.
 */
@Getter
public enum WebhookEventTypeEnum {

    RESERVACION_CREADA("reservation.created", "Reservación creada"),
    RESERVACION_ACTUALIZADA("reservation.updated", "Reservación modificada"),
    RESERVACION_CANCELADA("reservation.cancelled", "Reservación cancelada"),
    OCUPACION_ACTUALIZADA("room.occupancy.updated", "Cambio de ocupación de habitación");

    private final String tipo;
    private final String descripcion;

    WebhookEventTypeEnum(String tipo, String descripcion) {
        this.tipo = tipo;
        this.descripcion = descripcion;
    }

    /**
     * Resuelve el enum a partir del campo {@code notificationType}.
     *
     * @return el tipo, o {@code null} si MiniHotel envió uno que esta versión no conoce
     */
    public static WebhookEventTypeEnum fromTipo(String tipo) {
        if (tipo == null) return null;
        for (WebhookEventTypeEnum evento : values()) {
            if (evento.tipo.equalsIgnoreCase(tipo.trim())) return evento;
        }
        return null;
    }

    /** {@code true} si el tipo corresponde a un evento de reservación. */
    public boolean esDeReservacion() {
        return this != OCUPACION_ACTUALIZADA;
    }
}
