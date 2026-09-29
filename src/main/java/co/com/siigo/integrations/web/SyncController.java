package co.com.siigo.integrations.web;

import co.com.siigo.integrations.client.MiniHotelClient;
import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.minihotel.BookingResponse;
import co.com.siigo.integrations.dto.siigo.FacturaResponse;
import co.com.siigo.integrations.service.HotelContextService;
import co.com.siigo.integrations.service.ScheduledSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sincronizaciones manuales y consulta de la configuración multi-tenant.
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final ScheduledSyncService scheduledSyncService;
    private final HotelContextService hotelContextService;
    private final MiniHotelClient miniHotelClient;

    /**
     * Sincroniza un hotel para una fecha.
     * {@code GET /api/sync/hotel/{hotelKey}?date=2026-08-25}
     */
    @GetMapping("/hotel/{hotelKey}")
    public ResponseEntity<List<FacturaResponse>> syncHotel(@PathVariable String hotelKey,
                                                           @RequestParam String date) {
        return ResponseEntity.ok(scheduledSyncService.syncManual(hotelKey, date));
    }

    /**
     * Sincroniza un hotel para un rango de fechas.
     * {@code GET /api/sync/hotel/{hotelKey}/range?fromDate=...&toDate=...}
     */
    @GetMapping("/hotel/{hotelKey}/range")
    public ResponseEntity<List<FacturaResponse>> syncHotelRange(@PathVariable String hotelKey,
                                                                @RequestParam String fromDate,
                                                                @RequestParam String toDate) {
        return ResponseEntity.ok(scheduledSyncService.syncManual(hotelKey, fromDate, toDate));
    }

    /**
     * Sincroniza todos los hoteles activos para una fecha.
     */
    @GetMapping("/all-hotels")
    public ResponseEntity<List<FacturaResponse>> syncAllHotels(@RequestParam String date) {
        return ResponseEntity.ok(scheduledSyncService.syncAllHotelsManual(date));
    }

    /**
     * Sincroniza una reservación puntual de un hotel.
     */
    @GetMapping("/hotel/{hotelKey}/reservation/{reservationNumber}")
    public ResponseEntity<List<FacturaResponse>> syncReservation(@PathVariable String hotelKey,
                                                                 @PathVariable String reservationNumber) {
        return ResponseEntity.ok(scheduledSyncService.syncManualByReservation(hotelKey, reservationNumber));
    }

    /**
     * Devuelve los IDs de reservaciones con salida en el rango indicado, sin sincronizar.
     * El panel lo usa para conocer el total antes de procesar.
     */
    @GetMapping("/hotel/{hotelKey}/reservation-ids")
    public ResponseEntity<List<String>> getReservationIds(@PathVariable String hotelKey,
                                                          @RequestParam String fromDate,
                                                          @RequestParam String toDate) {
        try {
            if (!hotelContextService.setCurrentHotel(hotelKey)) {
                throw new IllegalArgumentException("Hotel no encontrado: " + hotelKey);
            }
            List<String> ids = miniHotelClient.getReservationsByDepartureDate(fromDate, toDate)
                    .stream()
                    .map(BookingResponse::getMinihotelReservationId)
                    .toList();
            return ResponseEntity.ok(ids);
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    @GetMapping("/hotels")
    public ResponseEntity<List<IntegrationProperties.HotelConfig>> getAllHotels() {
        return ResponseEntity.ok(hotelContextService.getAllHotels());
    }

    @GetMapping("/hotels/active")
    public ResponseEntity<List<IntegrationProperties.HotelConfig>> getActiveHotels() {
        return ResponseEntity.ok(hotelContextService.getActiveHotels());
    }

    @GetMapping("/hotels/{hotelKey}")
    public ResponseEntity<IntegrationProperties.HotelConfig> getHotel(@PathVariable String hotelKey) {
        return hotelContextService.findHotelByKey(hotelKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
