package co.com.siigo.integrations.service;

import co.com.siigo.integrations.client.MiniHotelClient;
import co.com.siigo.integrations.dto.minihotel.BalanceSummaryResponse;
import co.com.siigo.integrations.dto.minihotel.ReservationBalanceResponse;
import co.com.siigo.integrations.dto.minihotel.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio de ejemplo para trabajar con el balance de reservas
 */
@Service
@RequiredArgsConstructor
public class ReservationBalanceService {

    private final MiniHotelClient miniHotelClient;


    /**
     * Obtiene el balance de una reserva como objeto Java
     */
    public ReservationBalanceResponse getBalance(String reservationId) {
        return miniHotelClient.getReservationBalance(reservationId);
    }

    /**
     * Obtiene el XML raw del balance
     */
    public ReservationBalanceResponse getBalanceXml(String reservationId) {
        return miniHotelClient.getReservationBalance(reservationId);
    }

    /**
     * Obtiene el balance pendiente de pago
     */
    public double getBalanceDue(String reservationId) {
        ReservationBalanceResponse balance = getBalance(reservationId);
        return balance.getBalanceDue();
    }

    /**
     * Obtiene todas las transacciones de tipo cargo (débito)
     */
    public List<TransactionResponse> getCharges(String reservationId) {
        ReservationBalanceResponse balance = getBalance(reservationId);
        return balance.getTransactions().stream()
                .filter(TransactionResponse::isCharge)
                .collect(Collectors.toList());
    }

    /**
     * Obtiene todas las transacciones de tipo pago (crédito)
     */
    public List<TransactionResponse> getPayments(String reservationId) {
        ReservationBalanceResponse balance = getBalance(reservationId);
        return balance.getTransactions().stream()
                .filter(TransactionResponse::isPayment)
                .collect(Collectors.toList());
    }

    /**
     * Calcula el total de cargos
     */
    public double getTotalCharges(String reservationId) {
//        return getCharges(reservationId).stream()
//                .mapToDouble(Transaction::getAmount)
//                .sum();
        return 0;
    }

    /**
     * Obtiene transacciones por departamento
     */
    public List<TransactionResponse> getTransactionsByDepartment(String reservationId, String department) {
        ReservationBalanceResponse balance = getBalance(reservationId);
        return balance.getTransactions().stream()
                .filter(t -> t.getDepartment().equalsIgnoreCase(department))
                .collect(Collectors.toList());
    }

    /**
     * Verifica si la reserva tiene balance pendiente
     */
    public boolean hasOutstandingBalance(String reservationId) {
        return getBalanceDue(reservationId) > 0;
    }

    /**
     * Genera un resumen del balance
     */
    public BalanceSummaryResponse getBalanceSummary(String reservationId) {
        ReservationBalanceResponse balance = getBalance(reservationId);

        BalanceSummaryResponse summary = new BalanceSummaryResponse();
        summary.setReservationNumber(balance.getReservationNumber());
        summary.setCurrency(balance.getCurrency());
        summary.setTotalCharges(balance.getDebit());
        summary.setTotalPayments(balance.getCredit());
        summary.setBalanceDue(balance.getTotalDebit());
        summary.setTransactionCount(balance.getTransactions().size());
        summary.setChargeCount((int) balance.getTransactions().stream().filter(TransactionResponse::isCharge).count());
        summary.setPaymentCount((int) balance.getTransactions().stream().filter(TransactionResponse::isPayment).count());

        return summary;
    }


}