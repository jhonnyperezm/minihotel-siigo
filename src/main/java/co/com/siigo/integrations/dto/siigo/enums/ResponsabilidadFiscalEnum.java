package co.com.siigo.integrations.dto.siigo.enums;

import lombok.Getter;

/**
 * Responsabilidades fiscales admitidas por Siigo API.
 * Para huéspedes persona natural lo habitual es {@code R-99-PN}.
 */
@Getter
public enum ResponsabilidadFiscalEnum {

    NO_APLICA("R-99-PN", "No aplica - Otros"),
    GRAN_CONTRIBUYENTE("O-13", "Gran contribuyente"),
    AUTORRETENEDOR("O-15", "Autorretenedor"),
    AGENTE_RETENCION_IVA("O-23", "Agente de retención IVA"),
    REGIMEN_SIMPLE("O-47", "Régimen simple de tributación");

    private final String codigo;
    private final String descripcion;

    ResponsabilidadFiscalEnum(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }
}
