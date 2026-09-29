package co.com.siigo.integrations.dto.siigo.enums;

import lombok.Getter;

/**
 * Naturaleza del tercero en Siigo. Se envía en el campo {@code person_type}.
 */
@Getter
public enum PersonTypeEnum {

    PERSONA("Person"),
    EMPRESA("Company");

    private final String valor;

    PersonTypeEnum(String valor) {
        this.valor = valor;
    }
}
