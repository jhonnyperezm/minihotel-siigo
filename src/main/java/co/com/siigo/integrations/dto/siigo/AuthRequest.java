package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Cuerpo de la petición de autenticación de Siigo API (POST /auth).
 */
public record AuthRequest(
        @NotBlank String username,
        @NotBlank @JsonProperty("access_key") String accessKey
) {
}
