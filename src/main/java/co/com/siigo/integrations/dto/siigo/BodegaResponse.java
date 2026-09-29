package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Bodega de Siigo (GET /v1/warehouses). Solo aplica a productos con control de inventarios.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BodegaResponse(
        Long id,
        String name,
        Boolean active,
        @JsonProperty("has_movements") Boolean hasMovements
) {

    public boolean estaActiva() {
        return !Boolean.FALSE.equals(active);
    }
}
