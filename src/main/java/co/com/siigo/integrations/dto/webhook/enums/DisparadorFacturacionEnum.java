package co.com.siigo.integrations.dto.webhook.enums;

import lombok.Getter;

/**
 * Evento que dispara la facturación automática al recibirse un webhook.
 *
 * <p>Se configura con {@code integrations.webhooks.disparador}.
 */
@Getter
public enum DisparadorFacturacionEnum {

    /**
     * {@code room.occupancy.updated} con {@code occupied = false}. Es el valor por defecto:
     * MiniHotel advierte que el estado de la reservación no siempre refleja el estado real
     * de la habitación y recomienda apoyarse en {@code occupied}.
     */
    OCUPACION("Check-out real de la habitación (occupied = false)"),

    /**
     * {@code reservation.updated} con estado {@code OUT}. Más directo conceptualmente,
     * pero menos fiable según la propia documentación de MiniHotel.
     */
    RESERVA_OUT("Reservación con estado OUT"),

    /** Cualquiera de los dos anteriores. El primero que llegue dispara la factura. */
    AMBOS("Ocupación o estado OUT, lo que ocurra primero"),

    /**
     * No factura nada: solo registra los eventos recibidos. Útil para observar el tráfico
     * real durante unos días antes de activar la facturación automática.
     */
    NINGUNO("Solo registrar eventos, sin facturar");

    private final String descripcion;

    DisparadorFacturacionEnum(String descripcion) {
        this.descripcion = descripcion;
    }

    public boolean incluyeOcupacion() {
        return this == OCUPACION || this == AMBOS;
    }

    public boolean incluyeReservaOut() {
        return this == RESERVA_OUT || this == AMBOS;
    }
}
