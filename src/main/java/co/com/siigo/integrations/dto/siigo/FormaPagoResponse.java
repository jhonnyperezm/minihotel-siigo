package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Medio de pago de GET /v1/payment-types?document_type=FV.
 *
 * @param dueDate {@code true} si el medio maneja vencimiento; en ese caso la factura
 *                debe enviar {@code payments[].due_date}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FormaPagoResponse(
        Long id,
        String name,
        String type,
        Boolean active,
        @JsonProperty("due_date") Boolean dueDate
) {

    public boolean manejaVencimiento() {
        return Boolean.TRUE.equals(dueDate);
    }

    public boolean estaActivo() {
        return !Boolean.FALSE.equals(active);
    }
}
