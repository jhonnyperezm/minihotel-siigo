package co.com.siigo.integrations.web;

import co.com.siigo.integrations.dto.RetryRequest;
import co.com.siigo.integrations.dto.SyncTransactionResponse;
import co.com.siigo.integrations.dto.siigo.FacturaResponse;
import co.com.siigo.integrations.client.SiigoFacturaClient;
import co.com.siigo.integrations.service.HotelContextService;
import co.com.siigo.integrations.service.ScheduledSyncService;
import co.com.siigo.integrations.service.SyncTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Controlador REST para gestionar las transacciones de sincronización.
 * Permite consultar el historial de sincronizaciones y reintentar las fallidas.
 */
@RestController
@RequestMapping("/api/sync-transactions")
@RequiredArgsConstructor
public class SyncTransactionController {

    private final SyncTransactionService syncTransactionService;
    private final ScheduledSyncService scheduledSyncService;
    private final HotelContextService hotelContextService;
    private final SiigoFacturaClient facturaClient;

    /**
     * Busca transacciones por rango de fechas, con filtro opcional por estado.
     * <p>
     * Ejemplo: {@code GET /api/sync-transactions/search?startDate=2026-01-01T00:00:00&endDate=2026-01-31T23:59:59&status=FAILED}
     *
     * @param startDate fecha y hora de inicio (ISO 8601, p. ej. {@code 2026-01-01T00:00:00})
     * @param endDate   fecha y hora de fin (ISO 8601)
     * @param status    estado opcional: {@code PENDING}, {@code SUCCESS} o {@code FAILED}
     * @return lista de transacciones que cumplen los criterios
     */
    @GetMapping("/search")
    public ResponseEntity<List<SyncTransactionResponse>> searchByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(required = false) String status
    ) {
        List<SyncTransactionResponse> transactions;
        if (status != null && !status.isBlank()) {
            transactions = syncTransactionService.findByDateRangeAndStatus(startDate, endDate, status);
        } else {
            transactions = syncTransactionService.findByDateRange(startDate, endDate);
        }
        return ResponseEntity.ok(transactions);
    }

    /**
     * Obtiene todas las transacciones de sincronización ordenadas por fecha descendente.
     *
     * @return lista completa de transacciones
     */
    @GetMapping
    public ResponseEntity<List<SyncTransactionResponse>> getAllTransactions() {
        return ResponseEntity.ok(syncTransactionService.findAll());
    }

    /**
     * Obtiene una transacción de sincronización por su ID.
     *
     * @param id identificador de la transacción
     * @return la transacción si existe, o 404 si no se encuentra
     */
    @GetMapping("/{id}")
    public ResponseEntity<SyncTransactionResponse> getTransactionById(@PathVariable Long id) {
        return syncTransactionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Busca la transacción de sincronización asociada a un número de reservación.
     *
     * @param reservationNumber número de reservación de MiniHotel
     * @return la transacción si existe, o 404 si no se encuentra
     */
    @GetMapping("/reservation/{reservationNumber}")
    public ResponseEntity<SyncTransactionResponse> getTransactionByReservationNumber(
            @PathVariable String reservationNumber
    ) {
        return syncTransactionService.findByReservationNumber(reservationNumber)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Reintenta la sincronización de una transacción fallida o pendiente.
     * <p>
     * Si el body está vacío o el {@code customReservationNumber} coincide con el número original,
     * se reutiliza la misma transacción (resetea su estado a {@code PENDING} sin crear un nuevo registro).
     * Si se especifica un número de reservación diferente, se crea una nueva transacción y la original
     * permanece intacta en el historial.
     * <p>
     * Ejemplo: {@code POST /api/sync-transactions/42/retry}
     * Body (opcional): {@code { "customReservationNumber": "RES-999" }}
     *
     * @param id      ID de la transacción a reintentar
     * @param request body opcional con número de reservación alternativo
     * @return lista de facturas creadas en Siigo (normalmente una)
     * @throws org.springframework.web.server.ResponseStatusException 404 si la transacción no existe
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<List<FacturaResponse>> retryTransaction(
            @PathVariable Long id,
            @RequestBody(required = false) RetryRequest request
    ) {
        SyncTransactionResponse txn = syncTransactionService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transacción no encontrada: " + id));

        String reservationNumber = (request != null && request.customReservationNumber() != null
                && !request.customReservationNumber().isBlank())
                ? request.customReservationNumber()
                : txn.reservationNumber();

        // Si se usa el número de reservación original, reutilizamos la transacción existente.
        // Si se cambió el número, creamos una nueva transacción para no pisar el historial.
        Long existingTransactionId = (request == null || request.customReservationNumber() == null
                || request.customReservationNumber().isBlank()
                || request.customReservationNumber().equals(txn.reservationNumber()))
                ? id : null;

        List<FacturaResponse> responses =
                scheduledSyncService.retryReservation(txn.siigoTenant(), reservationNumber, existingTransactionId);

        return ResponseEntity.ok(responses);
    }

    /**
     * Consulta en Siigo el detalle de los errores de una factura electrónica rechazada
     * por la DIAN, para poder diagnosticarla sin salir del panel.
     *
     * @param id ID de la transacción de sincronización
     * @return respuesta cruda de {@code /v1/invoices/{id}/stamp/errors}
     */
    @GetMapping("/{id}/errores-dian")
    public ResponseEntity<Map<String, String>> erroresDian(@PathVariable Long id) {
        SyncTransactionResponse txn = syncTransactionService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transacción no encontrada: " + id));

        if (txn.siigoId() == null || txn.siigoId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La transacción " + id + " no tiene una factura creada en Siigo");
        }

        try {
            if (!hotelContextService.setCurrentHotelByTenant(txn.siigoTenant())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Hotel no encontrado para el tenant: " + txn.siigoTenant());
            }
            String detalle = facturaClient.consultarErroresDian(txn.siigoId());
            return ResponseEntity.ok(Map.of(
                    "siigoId", txn.siigoId(),
                    "estadoDian", txn.estadoDian() == null ? "desconocido" : txn.estadoDian(),
                    "detalle", detalle == null ? "Siigo no devolvió detalle de errores" : detalle));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Reenvía a la DIAN una factura que quedó en estado Draft o fue rechazada.
     *
     * @param id ID de la transacción de sincronización
     */
    @PostMapping("/{id}/enviar-dian")
    public ResponseEntity<Map<String, Object>> enviarDian(@PathVariable Long id) {
        SyncTransactionResponse txn = syncTransactionService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transacción no encontrada: " + id));

        if (txn.siigoId() == null || txn.siigoId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La transacción " + id + " no tiene una factura creada en Siigo");
        }

        try {
            if (!hotelContextService.setCurrentHotelByTenant(txn.siigoTenant())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Hotel no encontrado para el tenant: " + txn.siigoTenant());
            }

            boolean aceptada = facturaClient.enviarADian(txn.siigoId());
            String estado = aceptada ? "Accepted" : "Rejected";
            String cufe = facturaClient.obtenerFactura(txn.siigoId())
                    .filter(f -> f.stamp() != null)
                    .map(f -> f.stamp().cufe())
                    .orElse(null);

            syncTransactionService.actualizarEstadoDian(id, estado, cufe);

            return ResponseEntity.ok(Map.<String, Object>of("aceptada", aceptada, "estadoDian", estado));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }
}
