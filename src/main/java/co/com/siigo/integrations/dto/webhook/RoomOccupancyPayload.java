package co.com.siigo.integrations.dto.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Payload del evento {@code room.occupancy.updated}.
 *
 * <p>MiniHotel recomienda usar el campo {@code occupied} en lugar del estado de la
 * reservación, porque el estado no siempre refleja el estado real de la habitación.
 * Por eso este es el disparador de facturación por defecto de la integración:
 * {@code occupied = false} equivale al check-out efectivo.
 *
 * <p>En reservaciones de varias habitaciones llega un evento por habitación. La
 * deduplicación por reservación y la restricción única de {@code sync_transactions}
 * evitan que eso genere facturas repetidas.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoomOccupancyPayload(
        String reservationNumber,
        String status,
        List<Room> rooms,
        String timestamp
) {

    /** {@code true} si al menos una habitación quedó desocupada: el huésped salió. */
    public boolean hayDesocupacion() {
        return rooms != null && rooms.stream().anyMatch(Room::estaDesocupada);
    }

    /** {@code true} si todas las habitaciones del evento quedaron desocupadas. */
    public boolean todasDesocupadas() {
        return rooms != null && !rooms.isEmpty() && rooms.stream().allMatch(Room::estaDesocupada);
    }

    public List<Room> roomsOrEmpty() {
        return rooms == null ? List.of() : rooms;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Room(
            String roomNumber,
            String guestFirstName,
            String guestLastName,
            String countryCode,
            String idNumber,
            String email,
            String phone,
            String checkInDate,
            String checkOutDate,
            Boolean occupied
    ) {

        /**
         * {@code true} solo si MiniHotel informó explícitamente {@code occupied = false}.
         * Un {@code null} no se interpreta como desocupada: sin dato, no se factura.
         */
        public boolean estaDesocupada() {
            return Boolean.FALSE.equals(occupied);
        }

        public String nombreHuesped() {
            return ((guestFirstName == null ? "" : guestFirstName) + " "
                    + (guestLastName == null ? "" : guestLastName)).trim();
        }
    }
}
