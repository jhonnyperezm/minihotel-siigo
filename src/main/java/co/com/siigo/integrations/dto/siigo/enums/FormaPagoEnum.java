package co.com.siigo.integrations.dto.siigo.enums;

import lombok.Getter;

/**
 * Formas de pago lógicas de la integración.
 * <p>
 * En Siigo los medios de pago son configurables por empresa, así que el nombre real
 * se toma de {@code integrations.siigo.facturacion.forma-pago-*} y se resuelve contra
 * {@code /v1/payment-types?document_type=FV}. Estos valores son solo el respaldo.
 */
@Getter
public enum FormaPagoEnum {

    CONTADO("Efectivo"),
    CREDITO("Pagos");

    private final String nombrePorDefecto;

    FormaPagoEnum(String nombrePorDefecto) {
        this.nombrePorDefecto = nombrePorDefecto;
    }
}
