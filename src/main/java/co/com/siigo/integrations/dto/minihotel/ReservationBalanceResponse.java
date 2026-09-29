package co.com.siigo.integrations.dto.minihotel;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
public class ReservationBalanceResponse {
    private String reservationNumber;
    private String currency;
    private double debit;
    private double credit;
    private double totalDebit;
    private List<TransactionResponse> transactions;

    public ReservationBalanceResponse() {
        this.transactions = new ArrayList<>();
    }

    /**
     * Calcula el balance pendiente (Total Debit)
     */
    public double getBalanceDue() {
        return debit - credit;
    }
}
