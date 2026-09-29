package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tipo de comprobante de GET /v1/document-types?type=FV.
 * Es el equivalente al "prefijo de documento" de World Office: define numeración,
 * si el comprobante es electrónico y si exige centro de costos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TipoComprobanteResponse(
        Long id,
        String code,
        String name,
        String description,
        String type,
        Boolean active,
        String prefix,
        Integer consecutive,
        @JsonProperty("automatic_number") Boolean automaticNumber,
        @JsonProperty("seller_by_item") Boolean sellerByItem,
        @JsonProperty("cost_center") Boolean costCenter,
        @JsonProperty("cost_center_mandatory") Boolean costCenterMandatory,
        @JsonProperty("cost_center_default") Long costCenterDefault,
        @JsonProperty("electronic_type") String electronicType,
        Boolean decimals
) {

    /** {@code true} si el comprobante está marcado como electrónico en Siigo Nube. */
    public boolean esElectronico() {
        return electronicType != null && !"NoElectronic".equalsIgnoreCase(electronicType);
    }

    public boolean estaActivo() {
        return !Boolean.FALSE.equals(active);
    }
}
