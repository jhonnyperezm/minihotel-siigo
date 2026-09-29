package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Impuesto configurado en Siigo (GET /v1/taxes).
 *
 * @param type tipo del impuesto: {@code IVA}, {@code Retefuente}, {@code ReteIVA}, {@code ReteICA}, {@code InC}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImpuestoResponse(
        Long id,
        String name,
        String type,
        BigDecimal percentage,
        Boolean active
) {

    /** Porcentaje del impuesto; {@code 0} si Siigo no lo informa. */
    public BigDecimal porcentajeSeguro() {
        return percentage == null ? BigDecimal.ZERO : percentage;
    }

    public boolean esIva() {
        return type != null && "IVA".equalsIgnoreCase(type);
    }

    public boolean estaActivo() {
        return !Boolean.FALSE.equals(active);
    }
}
