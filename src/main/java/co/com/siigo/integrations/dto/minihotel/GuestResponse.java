package co.com.siigo.integrations.dto.minihotel;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GuestResponse {

    private String givenName;
    private String surname;
    private String email;
    private String phone;
    private String idNumber;
    private String address;
    private String city;
    private String zip;
    private String country;

    public String getFullName() {
        return givenName + " " + surname;
    }
}
