package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * Producto o servicio de Siigo (GET /v1/products). Reemplaza al inventario de World Office.
 * En la factura los renglones referencian el producto por {@code code}, no por id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductoResponse(
        String id,
        String code,
        String name,
        String type,
        Boolean active,
        @JsonProperty("stock_control") Boolean stockControl,
        Unidad unit,
        List<Impuesto> taxes
) {

    /** {@code true} si el producto maneja inventario; solo entonces se puede enviar bodega. */
    public boolean manejaInventario() {
        return Boolean.TRUE.equals(stockControl);
    }

    public boolean estaActivo() {
        return !Boolean.FALSE.equals(active);
    }

    /** Primer impuesto tipo IVA asociado al producto, si lo tiene configurado. */
    public java.util.Optional<Impuesto> ivaConfigurado() {
        return taxes == null ? java.util.Optional.empty()
                : taxes.stream().filter(t -> t.type() != null && "IVA".equalsIgnoreCase(t.type())).findFirst();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Unidad(String code, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Impuesto(Long id, String name, String type, BigDecimal percentage) {
    }
}
