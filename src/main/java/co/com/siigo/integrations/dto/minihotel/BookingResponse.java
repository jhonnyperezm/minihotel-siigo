package co.com.siigo.integrations.dto.minihotel;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class BookingResponse {
    private String portalReservationId;
    private String minihotelReservationId;
    private String status;
    private String source;
    private String createDateTime;
    private String arrivalTime;
    private String departureTime;
    private GuestResponse primaryGuest;
    private ResGlobalInfoResponse resGlobalInfo;

}
