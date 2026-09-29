package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Respuesta de POST /v1/invoices/{id}/stamp — envío de la factura electrónica a la DIAN.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EnvioDianResponse(
        String status,
        String cufe,
        String observations,
        String errors
) {

    public boolean aceptada() {
        return "Accepted".equalsIgnoreCase(status);
    }
}
