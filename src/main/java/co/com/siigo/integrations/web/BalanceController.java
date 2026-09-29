package co.com.siigo.integrations.web;

import co.com.siigo.integrations.dto.minihotel.BalanceSummaryResponse;
import co.com.siigo.integrations.dto.minihotel.ReservationBalanceResponse;
import co.com.siigo.integrations.dto.minihotel.TransactionResponse;
import co.com.siigo.integrations.service.HotelContextService;
import co.com.siigo.integrations.service.ReservationBalanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller REST para gestionar el balance de reservas.
 *
 * <p>Todos los endpoints aceptan {@code ?hotelKey=}; si se omite y hay un solo hotel activo,
 * se usa ese.
 */
@RestController
@RequestMapping("/api/balance")
public class BalanceController {

    private final ReservationBalanceService balanceService;
    private final HotelContextService hotelContextService;

    public BalanceController(ReservationBalanceService balanceService, HotelContextService hotelContextService) {
        this.balanceService = balanceService;
        this.hotelContextService = hotelContextService;
    }

    /**
     * Obtiene el balance completo en formato XML
     * GET /api/balance/{reservationId}/xml
     */
    @GetMapping("/{reservationId}/xml")
    public ResponseEntity<ReservationBalanceResponse> getBalanceXml(@PathVariable String reservationId,
            @RequestParam(required = false) String hotelKey) {
        ReservationBalanceResponse xmlResponse = hotelContextService.ejecutarEnHotel(hotelKey,
                () -> balanceService.getBalanceXml(reservationId));
        return ResponseEntity.ok(xmlResponse);
    }

    /**
     * Obtiene el balance completo como objeto JSON
     * GET /api/balance/{reservationId}
     */
    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationBalanceResponse> getBalance(@PathVariable String reservationId,
            @RequestParam(required = false) String hotelKey) {
        ReservationBalanceResponse balance = hotelContextService.ejecutarEnHotel(hotelKey,
                () -> balanceService.getBalance(reservationId));
        return ResponseEntity.ok(balance);
    }

    /**
     * Obtiene un resumen del balance
     * GET /api/balance/{reservationId}/summary
     */
    @GetMapping("/{reservationId}/summary")
    public ResponseEntity<BalanceSummaryResponse> getBalanceSummary(@PathVariable String reservationId,
            @RequestParam(required = false) String hotelKey) {
        BalanceSummaryResponse summary = hotelContextService.ejecutarEnHotel(hotelKey,
                () -> balanceService.getBalanceSummary(reservationId));
        return ResponseEntity.ok(summary);
    }

    /**
     * Obtiene el monto pendiente de pago
     * GET /api/balance/{reservationId}/due
     */
    @GetMapping("/{reservationId}/due")
    public ResponseEntity<Double> getBalanceDue(@PathVariable String reservationId,
            @RequestParam(required = false) String hotelKey) {
        double balanceDue = hotelContextService.ejecutarEnHotel(hotelKey,
                () -> balanceService.getBalanceDue(reservationId));
        return ResponseEntity.ok(balanceDue);
    }

    /**
     * Obtiene todas las transacciones de tipo cargo
     * GET /api/balance/{reservationId}/charges
     */
    @GetMapping("/{reservationId}/charges")
    public ResponseEntity<List<TransactionResponse>> getCharges(@PathVariable String reservationId,
            @RequestParam(required = false) String hotelKey) {
        List<TransactionResponse> charges = hotelContextService.ejecutarEnHotel(hotelKey,
                () -> balanceService.getCharges(reservationId));
        return ResponseEntity.ok(charges);
    }

    /**
     * Obtiene todas las transacciones de tipo pago
     * GET /api/balance/{reservationId}/payments
     */
    @GetMapping("/{reservationId}/payments")
    public ResponseEntity<List<TransactionResponse>> getPayments(@PathVariable String reservationId,
            @RequestParam(required = false) String hotelKey) {
        List<TransactionResponse> payments = hotelContextService.ejecutarEnHotel(hotelKey,
                () -> balanceService.getPayments(reservationId));
        return ResponseEntity.ok(payments);
    }

    /**
     * Obtiene el total de cargos
     * GET /api/balance/{reservationId}/charges/total
     */
    @GetMapping("/{reservationId}/charges/total")
    public ResponseEntity<Double> getTotalCharges(@PathVariable String reservationId,
            @RequestParam(required = false) String hotelKey) {
        double totalCharges = hotelContextService.ejecutarEnHotel(hotelKey,
                () -> balanceService.getTotalCharges(reservationId));
        return ResponseEntity.ok(totalCharges);
    }



    /**
     * Obtiene transacciones filtradas por departamento
     * GET /api/balance/{reservationId}/department/{department}
     */
    @GetMapping("/{reservationId}/department/{department}")
    public ResponseEntity<List<TransactionResponse>> getTransactionsByDepartment(
            @PathVariable String reservationId,
            @PathVariable String department,
            @RequestParam(required = false) String hotelKey) {
        List<TransactionResponse> transactions = hotelContextService.ejecutarEnHotel(hotelKey,
                () -> balanceService.getTransactionsByDepartment(reservationId, department));
        return ResponseEntity.ok(transactions);
    }

    /**
     * Verifica si tiene balance pendiente
     * GET /api/balance/{reservationId}/has-outstanding
     */
    @GetMapping("/{reservationId}/has-outstanding")
    public ResponseEntity<Boolean> hasOutstandingBalance(@PathVariable String reservationId,
            @RequestParam(required = false) String hotelKey) {
        boolean hasOutstanding = hotelContextService.ejecutarEnHotel(hotelKey,
                () -> balanceService.hasOutstandingBalance(reservationId));
        return ResponseEntity.ok(hasOutstanding);
    }
}