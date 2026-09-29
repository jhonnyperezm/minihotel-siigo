package co.com.siigo.integrations.dto.minihotel;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class TransactionResponse {
    // Getters y Setters
    private String account;
    private String date; // Formato: yyyyMMdd
    private String time; // Formato: hh:MM
    private String department;
    private int debitCredit; // 1 = Cargo, 2 = Pago
    private String details;
    private BigDecimal amount;

    /**
     * Retorna el tipo de transacción en texto
     */
    public String getTransactionType() {
        return debitCredit == 1 ? "CARGO" : "PAGO";
    }

    /**
     * Retorna true si es un cargo (débito)
     */
    public boolean isCharge() {
        return debitCredit == 1;
    }

    /**
     * Retorna true si es un pago (crédito)
     */
    public boolean isPayment() {
        return debitCredit == 2;
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "account='" + account + '\'' +
                ", date='" + date + '\'' +
                ", time='" + time + '\'' +
                ", department='" + department + '\'' +
                ", type='" + getTransactionType() + '\'' +
                ", details='" + details + '\'' +
                ", amount=" + amount +
                '}';
    }
}

