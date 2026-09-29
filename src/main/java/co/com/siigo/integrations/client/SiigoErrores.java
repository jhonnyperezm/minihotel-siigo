package co.com.siigo.integrations.client;

import co.com.siigo.integrations.dto.siigo.SiigoErrorResponse;
import co.com.siigo.integrations.dto.siigo.enums.SiigoErrorEnum;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Traduce los errores de Siigo API a mensajes legibles para la auditoría.
 *
 * <p>Siigo responde con un arreglo {@code Errors} donde cada elemento trae {@code Code},
 * {@code Message} y {@code Params}. Este helper aplana ese arreglo y, cuando el código es
 * conocido, añade una explicación en español tomada de {@link SiigoErrorEnum}.
 */
@Slf4j
final class SiigoErrores {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SiigoErrores() {
    }

    /**
     * Construye la excepción a propagar a partir de una respuesta de error de Siigo.
     *
     * @param operacion nombre de la operación, para ubicar el fallo en los logs
     * @param ex        excepción HTTP lanzada por RestTemplate
     */
    static RuntimeException traducir(String operacion, HttpStatusCodeException ex) {
        String detalle = describir(ex);
        log.error("Error {} en '{}': {}", ex.getStatusCode(), operacion, detalle);
        return new IllegalStateException(
                "Siigo rechazó la operación '" + operacion + "' (" + ex.getStatusCode() + "): " + detalle, ex);
    }

    /**
     * Extrae un mensaje legible del cuerpo de la respuesta de error.
     * Si el cuerpo no tiene el formato esperado, devuelve el texto crudo.
     */
    static String describir(HttpStatusCodeException ex) {
        String body = ex.getResponseBodyAsString();
        if (body == null || body.isBlank()) return ex.getStatusText();

        try {
            SiigoErrorResponse error = MAPPER.readValue(body, SiigoErrorResponse.class);
            if (error.errors() == null || error.errors().isEmpty()) return body;

            String mensaje = error.toMensaje();
            String ayuda = error.errors().stream()
                    .map(e -> SiigoErrorEnum.fromCodigo(e.code()))
                    .filter(java.util.Objects::nonNull)
                    .map(SiigoErrorEnum::getDescripcion)
                    .findFirst()
                    .orElse(null);

            return ayuda == null ? mensaje : mensaje + " -> " + ayuda;
        } catch (Exception parseError) {
            return body;
        }
    }
}
