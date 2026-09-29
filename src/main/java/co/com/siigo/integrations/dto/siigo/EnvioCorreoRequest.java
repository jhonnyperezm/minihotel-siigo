package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cuerpo de POST /v1/invoices/{id}/mail. Si no se indican destinatarios, Siigo usa
 * el correo registrado en el tercero de la factura.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnvioCorreoRequest(
        @JsonProperty("mail_to") String mailTo,
        @JsonProperty("copy_to") String copyTo
) {

    /** Envío al correo que el cliente tenga configurado en Siigo. */
    public static EnvioCorreoRequest porDefecto() {
        return new EnvioCorreoRequest(null, null);
    }
}
