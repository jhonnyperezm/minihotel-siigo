package co.com.siigo.integrations.dto.siigo.enums;

import lombok.Getter;

/**
 * Tipos de comprobante contable de Siigo, usados como query param {@code type}
 * en {@code /v1/document-types}.
 */
@Getter
public enum DocumentoTipoEnum {

    FACTURA_VENTA("FV", "Factura de venta"),
    NOTA_CREDITO("NC", "Nota crédito"),
    RECIBO_CAJA("RC", "Recibo de caja"),
    FACTURA_COMPRA("FC", "Factura de compra"),
    DOCUMENTO_SOPORTE("DS", "Documento soporte");

    private final String codigo;
    private final String descripcion;

    DocumentoTipoEnum(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String codigo() {
        return codigo;
    }
}
