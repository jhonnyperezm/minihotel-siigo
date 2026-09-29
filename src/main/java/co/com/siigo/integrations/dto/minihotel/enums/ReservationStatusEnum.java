package co.com.siigo.integrations.dto.minihotel.enums;

import lombok.Getter;

@Getter
public enum ReservationStatusEnum {
    OK("OK", "Confirmed"),
    WL("WL", "Pending"),
    IN("IN", "Checked-in"),
    OUT("OUT", "Checked-out"),
    CL("CL", "Cancelled"),
    BL("BL", "Black list"),
    OTHER("OTHER", "Custom status");

    private final String code;
    private final String description;

    ReservationStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Obtiene el enum a partir del código.
     *
     * @param code código del estado
     * @return el enum correspondiente o OTHER si no se encuentra
     */
    public static ReservationStatusEnum fromCode(String code) {
        for (ReservationStatusEnum status : values()) {
            if (status.getCode().equalsIgnoreCase(code)) {
                return status;
            }
        }
        return OTHER;
    }
}
