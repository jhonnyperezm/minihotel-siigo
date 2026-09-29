package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Formato de error de Siigo API. Ejemplo:
 * <pre>{"Errors":[{"Code":"parameter_required","Message":"El campo X es obligatorio","Params":["X"]}]}</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SiigoErrorResponse(
        @JsonProperty("Errors") @JsonAlias({"errors"}) List<Error> errors
) {

    /**
     * Aplana los errores en un único mensaje legible para guardar en la auditoría.
     */
    public String toMensaje() {
        if (errors == null || errors.isEmpty()) return "Error desconocido de Siigo";
        return errors.stream()
                .map(Error::describir)
                .collect(Collectors.joining(" | "));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(
            @JsonProperty("Code") @JsonAlias({"code"}) String code,
            @JsonProperty("Message") @JsonAlias({"message"}) String message,
            @JsonProperty("Params") @JsonAlias({"params"}) List<String> params
    ) {
        public String describir() {
            String detalle = params == null || params.isEmpty() ? "" : " " + params;
            return "[" + code + "] " + message + detalle;
        }
    }
}
