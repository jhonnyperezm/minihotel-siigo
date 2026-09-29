package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Tercero (cliente) tal como lo devuelve Siigo en GET/POST /v1/customers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClienteResponse(
        String id,
        String type,
        @JsonProperty("person_type") String personType,
        @JsonProperty("id_type") TipoIdentificacion idType,
        String identification,
        @JsonProperty("branch_office") Integer branchOffice,
        @JsonProperty("check_digit") String checkDigit,
        List<String> name,
        @JsonProperty("commercial_name") String commercialName,
        Boolean active
) {

    /** Nombre completo concatenado, útil para logs y para el panel de administración. */
    public String nombreCompleto() {
        return name == null ? "" : String.join(" ", name).trim();
    }

    /** Sucursal del cliente; {@code 0} cuando Siigo no la informa. */
    public int sucursal() {
        return branchOffice == null ? 0 : branchOffice;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TipoIdentificacion(String code, String name) {
    }
}
