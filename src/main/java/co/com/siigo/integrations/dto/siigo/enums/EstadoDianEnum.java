package co.com.siigo.integrations.dto.siigo.enums;

import lombok.Getter;

/**
 * Estados del documento electrónico en Siigo.
 */
@Getter
public enum EstadoDianEnum {

    DRAFT("Draft", "Guardada en Siigo pero no enviada a la DIAN (sin CUFE)"),
    ACCEPTED("Accepted", "Enviada y aceptada por la DIAN"),
    REJECTED("Rejected", "Enviada con errores y rechazada por la DIAN");

    private final String codigo;
    private final String descripcion;

    EstadoDianEnum(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static EstadoDianEnum fromCodigo(String codigo) {
        if (codigo == null) return null;
        for (EstadoDianEnum estado : values()) {
            if (estado.codigo.equalsIgnoreCase(codigo)) return estado;
        }
        return null;
    }
}
