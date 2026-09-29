package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Usuario de Siigo (GET /v1/users). El {@code id} de un usuario marcado como
 * vendedor es el que se envía en el campo {@code seller} de la factura.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UsuarioResponse(
        Long id,
        String username,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String email,
        String identification,
        Boolean active
) {

    public String nombreCompleto() {
        return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
    }

    public boolean estaActivo() {
        return !Boolean.FALSE.equals(active);
    }
}
