package co.com.siigo.integrations.dto.minihotel.enums;

import lombok.Getter;

import java.util.Arrays;

@Getter
public enum ReservationExcludedEnum {

    AIRBNB("Airbnb", Tipo.SOURCE),
    EXPEDIA("Expedia", Tipo.SOURCE),
    EFECTIVO_ZIP("Efectivo", Tipo.ZIP);

    public enum Tipo { SOURCE, ZIP }

    private final String value;
    private final Tipo tipo;

    ReservationExcludedEnum(String value, Tipo tipo) {
        this.value = value;
        this.tipo = tipo;
    }

    public static boolean isSourceExcluded(String source) {
        if (source == null) return false;
        String sourceLower = source.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(e -> e.tipo == Tipo.SOURCE)
                .anyMatch(e -> sourceLower.contains(e.getValue().toLowerCase()));
    }

    public static boolean isZipExcluded(String zip) {
        if (zip == null) return false;
        return Arrays.stream(values())
                .filter(e -> e.tipo == Tipo.ZIP)
                .anyMatch(e -> e.getValue().equalsIgnoreCase(zip));
    }
}
