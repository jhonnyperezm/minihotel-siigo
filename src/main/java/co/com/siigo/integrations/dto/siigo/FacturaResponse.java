package co.com.siigo.integrations.dto.siigo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Respuesta de Siigo al crear o consultar una factura de venta.
 * El {@code id} es un GUID, no un consecutivo numérico.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FacturaResponse(
        String id,
        String name,
        String prefix,
        Integer number,
        String date,
        BigDecimal total,
        BigDecimal balance,
        Cliente customer,
        Sello stamp,
        Correo mail,
        Boolean annulled,
        Metadatos metadata
) {

    /** Nombre visible del documento (p. ej. {@code FV-2-22}); si falta, se arma con prefijo y número. */
    public String numeroDocumento() {
        if (name != null && !name.isBlank()) return name;
        if (prefix != null && number != null) return prefix + "-" + number;
        return number != null ? String.valueOf(number) : id;
    }

    /** {@code true} si la DIAN ya aceptó el documento electrónico. */
    public boolean aceptadaPorDian() {
        return stamp != null && "Accepted".equalsIgnoreCase(stamp.status());
    }

    /** {@code true} si el documento fue anulado en Siigo. */
    public boolean anulada() {
        return Boolean.TRUE.equals(annulled);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cliente(
            String id,
            String identification,
            @JsonProperty("branch_office") Integer branchOffice
    ) {
    }

    /**
     * Estado del documento electrónico.
     * Valores posibles: {@code Draft} (no enviado), {@code Accepted}, {@code Rejected}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sello(
            String status,
            String cufe,
            String cude,
            String observations,
            String errors
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Correo(String status, String observations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadatos(
            String created,
            @JsonProperty("last_updated") String lastUpdated
    ) {
    }
}
