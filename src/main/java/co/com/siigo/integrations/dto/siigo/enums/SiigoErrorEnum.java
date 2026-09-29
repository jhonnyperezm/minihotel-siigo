package co.com.siigo.integrations.dto.siigo.enums;

import lombok.Getter;

/**
 * Errores frecuentes de Siigo API, con una explicación en español para el panel de auditoría.
 */
@Getter
public enum SiigoErrorEnum {

    PARAMETER_REQUIRED("parameter_required",
            "Falta un campo obligatorio en la petición. Revisa el detalle del error para saber cuál."),

    PARAMETER_INVALID("parameter_invalid",
            "Un campo trae un valor no permitido. Verifica contra los catálogos de Siigo Nube."),

    NOT_FOUND("not_found",
            "El recurso referenciado no existe en Siigo Nube (cliente, producto, comprobante o medio de pago)."),

    ALREADY_EXISTS("already_exists",
            "El recurso ya existe en Siigo Nube. Para terceros, valida la identificación y la sucursal."),

    UNAUTHORIZED("unauthorized",
            "Token inválido o expirado. La integración renovará el token y reintentará."),

    TOO_MANY_REQUESTS("too_many_requests",
            "Se superó el límite de peticiones (100 por minuto en producción). Espaciar los envíos."),

    STOCK_CONTROL("stock_control",
            "Se envió bodega para un producto que no maneja control de inventarios."),

    ELECTRONIC_INVOICE_ERROR("electronic_invoice_error",
            "La DIAN rechazó el documento electrónico. Consulta /v1/invoices/{id}/stamp/errors.");

    private final String codigo;
    private final String descripcion;

    SiigoErrorEnum(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public static SiigoErrorEnum fromCodigo(String codigo) {
        if (codigo == null) return null;
        for (SiigoErrorEnum error : values()) {
            if (error.codigo.equalsIgnoreCase(codigo)) return error;
        }
        return null;
    }

    public static boolean isKnownError(String codigo) {
        return fromCodigo(codigo) != null;
    }
}
