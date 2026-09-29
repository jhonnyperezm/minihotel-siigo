package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Cuerpo de POST /v1/customers — creación de un tercero (cliente) en Siigo.
 *
 * @param type                    {@code Customer}, {@code Supplier} u {@code Other}
 * @param personType              {@code Person} o {@code Company}
 * @param idType                  código del tipo de documento (13 = cédula, 31 = NIT, 41 = pasaporte…)
 * @param identification          número de identificación, sin caracteres especiales
 * @param name                    para {@code Person} son dos elementos (nombres, apellidos);
 *                                para {@code Company} un solo elemento con la razón social
 * @param fiscalResponsibilities  responsabilidades fiscales; lo habitual es {@code R-99-PN}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrearClienteRequest(
        String type,
        @NotBlank @JsonProperty("person_type") String personType,
        @NotBlank @JsonProperty("id_type") String idType,
        @NotBlank String identification,
        @JsonProperty("check_digit") String checkDigit,
        @NotNull List<String> name,
        @JsonProperty("commercial_name") String commercialName,
        @JsonProperty("branch_office") Integer branchOffice,
        Boolean active,
        @JsonProperty("vat_responsible") Boolean vatResponsible,
        @JsonProperty("fiscal_responsibilities") List<ResponsabilidadFiscal> fiscalResponsibilities,
        Direccion address,
        List<Telefono> phones,
        List<Contacto> contacts,
        String comments
) {

    public record ResponsabilidadFiscal(String code) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Direccion(
            String address,
            Ciudad city,
            @JsonProperty("postal_code") String postalCode
    ) {
    }

    /**
     * Ciudad en códigos DANE/Siigo. Ejemplo Bogotá: {@code CO / 11 / 11001}.
     */
    public record Ciudad(
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("state_code") String stateCode,
            @JsonProperty("city_code") String cityCode
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Telefono(String indicative, String number, String extension) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Contacto(
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            String email
    ) {
    }
}
