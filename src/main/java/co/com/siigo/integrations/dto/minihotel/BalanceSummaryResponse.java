package co.com.siigo.integrations.dto.minihotel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BalanceSummaryResponse {
    private String reservationNumber;
    private String currency;
    private double totalCharges;
    private double totalPayments;
    private double balanceDue;
    private int transactionCount;
    private int chargeCount;
    private int paymentCount;
}
