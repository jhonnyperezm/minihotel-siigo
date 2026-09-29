package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Centro de costos de Siigo (GET /v1/cost-centers).
 * Es la forma natural de separar la operación de cada hotel dentro de una misma empresa.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CentroCostoResponse(
        Long id,
        String code,
        String name,
        Boolean active
) {

    public boolean estaActivo() {
        return !Boolean.FALSE.equals(active);
    }
}
