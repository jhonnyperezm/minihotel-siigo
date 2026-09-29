package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cuerpo de POST /v1/invoices — creación de una factura de venta en Siigo.
 * <p>
 * Los campos nulos no se serializan, de modo que los opcionales (centro de costos,
 * moneda extranjera, bodega, descuentos) solo viajan cuando están configurados.
 *
 * @param document     tipo de comprobante (id obtenido de {@code /v1/document-types?type=FV})
 * @param number       consecutivo; {@code null} deja que Siigo lo asigne automáticamente
 * @param date         fecha del documento en formato {@code yyyy-MM-dd}
 * @param customer     cliente ya existente en Siigo (referenciado por identificación)
 * @param costCenter   id del centro de costos (opcional)
 * @param currency     moneda extranjera y TRM (opcional; por defecto la moneda local)
 * @param seller       id del vendedor obtenido de {@code /v1/users}
 * @param observations observaciones del documento (máx. 4.000 caracteres)
 * @param items        renglones de la factura
 * @param payments     medios de pago; la suma debe igualar el total de la factura
 * @param stamp        {@code send=true} envía la factura electrónica a la DIAN
 * @param mail         {@code send=true} envía la factura por correo al cliente
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrearFacturaRequest(
        @NotNull Documento document,
        Integer number,
        @NotNull String date,
        @NotNull Cliente customer,
        @JsonProperty("cost_center") Long costCenter,
        Moneda currency,
        @NotNull Long seller,
        String observations,
        @NotEmpty List<Item> items,
        @NotEmpty List<Pago> payments,
        Sello stamp,
        Correo mail
) {

    /** Tipo de comprobante. */
    public record Documento(@NotNull Long id) {
    }

    /** Referencia al cliente. En Siigo la factura apunta al tercero por su identificación. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cliente(
            @NotNull String identification,
            @JsonProperty("branch_office") Integer branchOffice
    ) {
    }

    /** Moneda extranjera. Requiere tener configurada moneda extranjera en Siigo Nube. */
    public record Moneda(
            String code,
            @JsonProperty("exchange_rate") BigDecimal exchangeRate
    ) {
    }

    /** Referencia a un impuesto del catálogo {@code /v1/taxes}. */
    public record Impuesto(@NotNull Long id) {
    }

    /**
     * Renglón de la factura.
     *
     * @param code      código del producto/servicio, debe existir y estar activo en Siigo
     * @param price     precio unitario SIN impuestos
     * @param warehouse id de bodega; solo si el producto maneja control de inventarios
     * @param taxes     impuestos aplicados al renglón (IVA, INC…)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            @NotNull String code,
            String description,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal price,
            Long warehouse,
            BigDecimal discount,
            List<Impuesto> taxes
    ) {
    }

    /**
     * Medio de pago.
     *
     * @param id      id obtenido de {@code /v1/payment-types?document_type=FV}
     * @param value   valor pagado con ese medio
     * @param dueDate obligatorio solo si el medio de pago maneja vencimiento
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Pago(
            @NotNull Long id,
            @NotNull BigDecimal value,
            @JsonProperty("due_date") String dueDate
    ) {
    }

    /** Envío del documento electrónico a la DIAN. */
    public record Sello(Boolean send) {
    }

    /** Envío del documento por correo al cliente. */
    public record Correo(Boolean send) {
    }
}
