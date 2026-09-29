package co.com.siigo.integrations.dto.webhook;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Payload de los eventos {@code reservation.created}, {@code reservation.updated} y
 * {@code reservation.cancelled}.
 *
 * <p><b>Advertencia importante:</b> la estructura completa (con {@code header}, {@code members}
 * y {@code total}) solo llega de forma fiable en {@code reservation.created}. En los ejemplos
 * de actualización y cancelación de MiniHotel el payload viene reducido a número de reserva,
 * origen, estado y marca de tiempo. Por eso esta integración usa el webhook como
 * <em>disparador</em> y siempre consulta los cargos con {@code GetReservationBalance()};
 * nunca factura con los datos del webhook.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationEventPayload(
        String reservationNumber,
        String source,
        String status,
        Header header,
        List<Member> members,
        OtaInfo otaInfo,
        Total total,
        String timestamp
) {

    /** Estado de reservación cancelada según MiniHotel. */
    public static final String ESTADO_CANCELADA = "CL";
    /** Estado de reservación con check-out realizado. */
    public static final String ESTADO_SALIDA = "OUT";
    /** Estado de reservación con huésped en casa. */
    public static final String ESTADO_EN_CASA = "IN";

    public boolean estaCancelada() {
        return ESTADO_CANCELADA.equalsIgnoreCase(status);
    }

    /** {@code true} si la reservación reporta check-out, que es el disparador de facturación. */
    public boolean tieneSalida() {
        return ESTADO_SALIDA.equalsIgnoreCase(status);
    }

    /**
     * {@code true} si el payload trae la estructura completa. Los eventos de actualización
     * y cancelación suelen venir reducidos.
     */
    public boolean esCompleto() {
        return header != null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(
            String firstName,
            String lastName,
            String idNumber,
            String email,
            String phone,
            String countryCode,
            String city,
            String checkInDate,
            String checkOutDate,
            List<Room> rooms
    ) {
        public String nombreCompleto() {
            return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Room(String roomNumber, String roomType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(
            String status,
            String serial,
            String roomNumber,
            String firstName,
            String lastName,
            String idNumber,
            String email,
            String phone,
            String checkInDate,
            String checkOutDate,
            GuestCount guestCount
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuestCount(Integer adults, Integer childs, Integer babies, Integer youth) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OtaInfo(String portalResNumber, String portal, String portalInternalId) {
    }

    /**
     * Total de la reservación.
     *
     * <p>La tabla de la documentación nombra el campo {@code amount}, pero el ejemplo JSON
     * de reserva nueva usa {@code amountAfterTaxes}. Se aceptan ambos.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Total(
            @JsonAlias({"amountAfterTaxes"}) BigDecimal amount,
            String currency
    ) {
    }
}
