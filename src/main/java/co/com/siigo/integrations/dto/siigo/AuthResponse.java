package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Respuesta de POST /auth. El token JWT tiene vigencia de 24 horas (86400 s).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("token_type") String tokenType,
        String scope
) {
}
