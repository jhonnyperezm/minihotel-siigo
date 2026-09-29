package co.com.siigo.integrations.dto.minihotel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResGlobalInfoResponse {
    private String arrival;
    private String departure;
    private String amountAfterTaxes;
    private String currencyCode;
}
