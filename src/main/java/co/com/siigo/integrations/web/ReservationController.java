package co.com.siigo.integrations.web;

import co.com.siigo.integrations.client.MiniHotelClient;
import co.com.siigo.integrations.dto.minihotel.ReservationBalanceResponse;
import co.com.siigo.integrations.service.HotelContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST para consultar reservaciones directamente en MiniHotel.
 * Útil para diagnóstico y verificación de datos antes de sincronizar.
 * Todos los endpoints delegan directamente al {@link MiniHotelClient}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hotel/{hotelKey}/reservations")
public class ReservationController {

    private final MiniHotelClient miniHotelApiClient;
    private final HotelContextService hotelContextService;

    /**
     * Busca reservaciones por correo electrónico del huésped.
     */
    @GetMapping("/by-email")
    public ResponseEntity<String> getReservationByEmail(
            @PathVariable String hotelKey,
            @RequestParam String email) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationByEmail(email));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Busca reservaciones por número de pasaporte del huésped.
     */
    @GetMapping("/by-passport")
    public ResponseEntity<String> getReservationByPassport(
            @PathVariable String hotelKey,
            @RequestParam String passportNumber) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationByPassport(passportNumber));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Busca una reservación por su ID interno de MiniHotel.
     */
    @GetMapping("/by-minihotel-id")
    public ResponseEntity<?> getReservationByMinihotelId(
            @PathVariable String hotelKey,
            @RequestParam String minihotelId) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationByMinihotelId(minihotelId));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Busca una reservación por su ID interno de MiniHotel en XML crudo.
     */
    @GetMapping(value = "/by-minihotel-id/raw", produces = org.springframework.http.MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getReservationByMinihotelIdRaw(
            @PathVariable String hotelKey,
            @RequestParam String minihotelId) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationByMinihotelIdRaw(minihotelId));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Busca reservaciones por nombre del huésped y fecha de llegada.
     */
    @GetMapping("/by-name-and-date")
    public ResponseEntity<String> getReservationByNameAndDate(
            @PathVariable String hotelKey,
            @RequestParam String givenName,
            @RequestParam String surname,
            @RequestParam String arrivalDate) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationByNameAndDate(givenName, surname, arrivalDate));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Busca reservaciones por rango de fecha de llegada (check-in).
     */
    @GetMapping("/by-date-range")
    public ResponseEntity<?> getReservationsByDateRange(
            @PathVariable String hotelKey,
            @RequestParam String fromDate,
            @RequestParam String toDate) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationsByDateRange(fromDate, toDate));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Busca reservaciones por rango de fecha de salida (check-out).
     */
    @GetMapping("/by-date-range-out")
    public ResponseEntity<?> getReservationsByDateRangeout(
            @PathVariable String hotelKey,
            @RequestParam String fromDate,
            @RequestParam String toDate) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationsByDepartureDate(fromDate, toDate));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Busca reservaciones por número de habitación, estado y rango de fechas.
     */
    @GetMapping("/by-room-and-status")
    public ResponseEntity<String> getReservationByRoomAndStatus(
            @PathVariable String hotelKey,
            @RequestParam String roomNumber,
            @RequestParam String status,
            @RequestParam String fromDate,
            @RequestParam String toDate) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationByRoomAndStatus(roomNumber, status, fromDate, toDate));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Obtiene el balance de cargos de una reservación.
     */
    @GetMapping("/balance/{reservationId}")
    public ResponseEntity<ReservationBalanceResponse> getReservationBalance(
            @PathVariable String hotelKey,
            @PathVariable String reservationId) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationBalance(reservationId));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Obtiene el balance de cargos en XML crudo de MiniHotel.
     */
    @GetMapping(value = "/balance/{reservationId}/raw", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getReservationBalanceRaw(
            @PathVariable String hotelKey,
            @PathVariable String reservationId) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelApiClient.getReservationBalanceRaw(reservationId));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }
}
