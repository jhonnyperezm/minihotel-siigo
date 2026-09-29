package co.com.siigo.integrations.dto.siigo.enums;

import lombok.Getter;

/**
 * Códigos de tipo de identificación aceptados por Siigo API para Colombia.
 * Se envían como texto en el campo {@code id_type} al crear un tercero.
 */
@Getter
public enum TipoIdentificacionEnum {

    REGISTRO_CIVIL("11", "Registro civil"),
    TARJETA_IDENTIDAD("12", "Tarjeta de identidad"),
    CEDULA_CIUDADANIA("13", "Cédula de ciudadanía"),
    TARJETA_EXTRANJERIA("21", "Tarjeta de extranjería"),
    CEDULA_EXTRANJERIA("22", "Cédula de extranjería"),
    NIT("31", "NIT"),
    PASAPORTE("41", "Pasaporte"),
    DOCUMENTO_EXTRANJERO("42", "Documento de identificación extranjero"),
    SIN_IDENTIFICACION_EXTERIOR("43", "Sin identificación del exterior"),
    PERMISO_ESPECIAL_PERMANENCIA("47", "Permiso especial de permanencia (PEP)"),
    PERMISO_PROTECCION_TEMPORAL("48", "Permiso de protección temporal (PPT)"),
    NIT_OTRO_PAIS("50", "NIT de otro país"),
    NUIP("91", "NUIP");

    private final String codigo;
    private final String descripcion;

    TipoIdentificacionEnum(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    /**
     * {@code true} si el documento corresponde a una persona jurídica.
     * Determina el valor de {@code person_type} al crear el tercero.
     */
    public boolean esEmpresa() {
        return this == NIT || this == NIT_OTRO_PAIS;
    }

    public PersonTypeEnum personType() {
        return esEmpresa() ? PersonTypeEnum.EMPRESA : PersonTypeEnum.PERSONA;
    }

    public static TipoIdentificacionEnum fromCodigo(String codigo) {
        if (codigo == null) return null;
        for (TipoIdentificacionEnum tipo : values()) {
            if (tipo.codigo.equals(codigo.trim())) return tipo;
        }
        return null;
    }
}
