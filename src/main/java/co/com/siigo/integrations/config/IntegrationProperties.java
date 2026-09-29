package co.com.siigo.integrations.config;

import co.com.siigo.integrations.dto.webhook.enums.DisparadorFacturacionEnum;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuración de la integración MiniHotel -> Siigo.
 *
 * <p><b>Diferencia clave con World Office:</b> en World Office un solo token daba acceso
 * a todas las empresas de la licencia y cada hotel se resolvía por {@code empresaId}.
 * En Siigo <em>cada empresa es un tenant independiente con sus propias credenciales</em>
 * ({@code username} + {@code access_key}), por lo que la autenticación y los catálogos
 * se manejan por hotel.
 *
 * <p>Si varios hoteles facturan bajo la misma empresa de Siigo, comparten credenciales y
 * se separan por centro de costos ({@code centro-costo-id}).
 */
@Data
@Validated
@ConfigurationProperties(prefix = "integrations")
public class IntegrationProperties {

    private Retry retry = new Retry();
    private MiniHotel minihotel = new MiniHotel();
    private Siigo siigo = new Siigo();
    private Webhooks webhooks = new Webhooks();

    /** Configuración multi-tenant: un elemento por hotel de MiniHotel. */
    private List<HotelConfig> hotels = new ArrayList<>();

    @Data
    public static class Retry {
        @Min(1)
        private int maxAttempts = 3;
        @Min(0)
        private long backoffMs = 800;
    }

    @Data
    public static class MiniHotel {
        @NotBlank
        private String baseUrl;
        @Min(100)
        private int timeoutMs = 20000;

        @NotBlank
        private String reservationBalancePath = "/reservations/{reservationNumber}/balance";
    }

    /**
     * Webhooks entrantes de MiniHotel.
     *
     * <p>MiniHotel envía notificaciones en tiempo real a un endpoint propio, autenticado con
     * Basic Auth. Hay que solicitarle a MiniHotel el alta del endpoint indicando la URL y las
     * credenciales; no se configura desde su panel.
     */
    @Data
    public static class Webhooks {

        /** Si es {@code false}, el endpoint responde 503 y MiniHotel reintentará más tarde. */
        private boolean enabled = false;

        /**
         * Credenciales dedicadas para MiniHotel. Conviene que sean distintas de las del panel
         * para poder rotarlas sin perder el acceso administrativo.
         */
        private String username;
        private String password;

        /** Evento que dispara la facturación automática. */
        private DisparadorFacturacionEnum disparador = DisparadorFacturacionEnum.OCUPACION;

        /**
         * Si es {@code false}, los eventos se guardan pero no se facturan. Equivale a
         * {@code disparador: NINGUNO} y sirve como interruptor de emergencia.
         */
        private boolean facturacionAutomatica = true;

        /**
         * Si es {@code true}, una reservación cancelada que ya tenga factura emitida se marca
         * para revisión manual. No se anula nada de forma automática: en Colombia eso exige
         * una nota crédito, que es una decisión contable y no técnica.
         */
        private boolean alertarCancelaciones = true;

        /** Días que se conservan los eventos antes de la limpieza automática. */
        @Min(1)
        private int retenerDias = 90;

        /** Tamaño máximo del JSON que se guarda en la auditoría, en caracteres. */
        @Min(1000)
        private int maxPayloadChars = 60000;

        public boolean credencialesConfiguradas() {
            return username != null && !username.isBlank()
                    && password != null && !password.isBlank();
        }
    }

    @Data
    public static class Siigo {
        /** Host de la API. Producción: {@code https://api.siigo.com}. */
        @NotBlank
        private String baseUrl = "https://api.siigo.com";
        @Min(100)
        private int timeoutMs = 30000;

        /** Ruta de autenticación. El token JWT devuelto dura 24 horas. */
        @NotBlank
        private String authPath = "/auth";

        /**
         * Margen de seguridad para renovar el token antes de que expire.
         * Con 300 s se renueva 5 minutos antes del vencimiento.
         */
        @Min(0)
        private long tokenRefreshMarginSeconds = 300;

        /** Credenciales por defecto, usadas por los hoteles que no definan las suyas. */
        private Credenciales auth = new Credenciales();

        /** Parámetros de facturación por defecto, sobrescribibles por hotel. */
        private Facturacion facturacion = new Facturacion();

        /**
         * Si es {@code true}, precarga los catálogos de todos los hoteles activos al arrancar.
         * Por defecto es {@code false}: cada tenant carga sus catálogos la primera vez que se usa,
         * de modo que un problema de credenciales no impida el arranque.
         */
        private boolean precargarCatalogos = false;
    }

    /**
     * Credenciales de Siigo API. Se generan en Siigo Nube, menú Alianzas, botón "Mi Credencial API".
     */
    @Data
    public static class Credenciales {
        private String username;
        private String accessKey;
        /**
         * Identificador de la aplicación registrada. Siigo exige enviarlo en el header
         * {@code Partner-Id} de todas las peticiones.
         */
        private String partnerId;

        public boolean estanCompletas() {
            return username != null && !username.isBlank()
                    && accessKey != null && !accessKey.isBlank();
        }
    }

    /**
     * Parámetros contables de la factura. Todo se resuelve contra los catálogos de Siigo:
     * si se indica el id se usa directamente; si se indica el nombre o código, se busca.
     */
    @Data
    public static class Facturacion {

        /** Código del comprobante FV en Siigo Nube (campo {@code code} de /v1/document-types). */
        private String tipoComprobanteCodigo;
        /** Alternativa directa: id del comprobante, evitando la búsqueda por código. */
        private Long tipoComprobanteId;

        /** Identificación, usuario o correo del vendedor a asociar a la factura. */
        private String vendedorIdentificacion;
        /** Alternativa directa: id del usuario vendedor (/v1/users). */
        private Long vendedorId;

        /** Centro de costos con el que se separa la operación de cada hotel. */
        private Long centroCostoId;

        /** Bodega para renglones de productos con control de inventarios. */
        private Long bodegaId;

        /** Código del producto/servicio usado para huéspedes nacionales (con IVA). */
        private String productoNacional = "ALOJAMIENTO";

        /** Código del producto/servicio usado para huéspedes del exterior (exento de IVA). */
        private String productoExtranjero = "ALOJAMIENTO-EXENTO";

        /** Nombre del impuesto de IVA en Siigo. Solo se aplica a huéspedes nacionales. */
        private String impuestoIva = "IVA 19%";
        /** Alternativa directa: id del impuesto (/v1/taxes). */
        private Long impuestoIvaId;

        /** Medio de pago en Siigo Nube para reservas sin {@code zip} (código 1). */
        private String formaPagoContado = "Efectivo";
        /** Alternativa directa y recomendada: id del medio de pago (/v1/payment-types). No cambia si lo renombran. */
        private Long formaPagoContadoId = 7027L;

        /** Medio de pago en Siigo Nube para reservas con {@code zip} (código 8). */
        private String formaPagoCredito = "Movimiento Bancario";
        /** Alternativa directa y recomendada: id del medio de pago (/v1/payment-types). No cambia si lo renombran. */
        private Long formaPagoCreditoId = 23640L;

        /** Identificación del tercero genérico para reservas sin documento. */
        private String consumidorFinalIdentificacion = "222222222222";

        /** Sucursal del cliente a referenciar en la factura. */
        private int sucursalCliente = 0;

        /**
         * Ciudad a usar cuando MiniHotel no informa una ciudad reconocible.
         * Debe coincidir con una constante de {@code CiudadSiigo}.
         */
        private String ciudadPorDefecto = "Bogotá D.C.";

        /** Si es {@code true}, la factura se envía a la DIAN al crearla ({@code stamp.send}). */
        private boolean enviarDian = false;

        /** Si es {@code true}, Siigo envía la factura por correo al huésped ({@code mail.send}). */
        private boolean enviarEmail = false;

        /**
         * Moneda local de la empresa. Si la reserva viene en otra moneda se envía el bloque
         * {@code currency}, que exige tener configurada moneda extranjera en Siigo Nube.
         */
        private String monedaLocal = "COP";

        /** TRM a usar cuando la reserva está en moneda extranjera. */
        private BigDecimal trm = BigDecimal.ONE;

        /**
         * Si es {@code true}, la factura se emite con la fecha de hoy en lugar de la fecha
         * de salida de la reserva. Útil porque la DIAN rechaza facturas electrónicas
         * con fecha anterior a la actual.
         */
        private boolean usarFechaActual = false;
    }

    /**
     * Parámetros de facturación de un hotel, sobrepuestos a los globales.
     *
     * <p>A diferencia de {@link Facturacion}, ningún campo tiene valor por defecto: {@code null}
     * (o un texto vacío) significa "no definido en el hotel" y hace que se herede el global.
     * Sin esta distinción no habría forma de saber si un {@code false} vino del YAML o del
     * valor inicial del campo.
     */
    @Data
    public static class FacturacionHotel {

        private String tipoComprobanteCodigo;
        private Long tipoComprobanteId;
        private String vendedorIdentificacion;
        private Long vendedorId;
        private Long centroCostoId;
        private Long bodegaId;
        private String productoNacional;
        private String productoExtranjero;
        private String impuestoIva;
        private Long impuestoIvaId;
        private String formaPagoContado;
        private Long formaPagoContadoId;
        private String formaPagoCredito;
        private Long formaPagoCreditoId;
        private String consumidorFinalIdentificacion;
        private Integer sucursalCliente;
        private String ciudadPorDefecto;
        private Boolean enviarDian;
        private Boolean enviarEmail;
        private String monedaLocal;
        private BigDecimal trm;
        private Boolean usarFechaActual;

        /** Devuelve una configuración nueva: la del hotel donde esté definida, la global en el resto. */
        public Facturacion combinarCon(Facturacion global) {
            var resultado = new Facturacion();
            resultado.setTipoComprobanteCodigo(texto(tipoComprobanteCodigo, global.getTipoComprobanteCodigo()));
            resultado.setTipoComprobanteId(valor(tipoComprobanteId, global.getTipoComprobanteId()));
            resultado.setVendedorIdentificacion(texto(vendedorIdentificacion, global.getVendedorIdentificacion()));
            resultado.setVendedorId(valor(vendedorId, global.getVendedorId()));
            resultado.setCentroCostoId(valor(centroCostoId, global.getCentroCostoId()));
            resultado.setBodegaId(valor(bodegaId, global.getBodegaId()));
            resultado.setProductoNacional(texto(productoNacional, global.getProductoNacional()));
            resultado.setProductoExtranjero(texto(productoExtranjero, global.getProductoExtranjero()));
            resultado.setImpuestoIva(texto(impuestoIva, global.getImpuestoIva()));
            resultado.setImpuestoIvaId(valor(impuestoIvaId, global.getImpuestoIvaId()));
            resultado.setFormaPagoContado(texto(formaPagoContado, global.getFormaPagoContado()));
            resultado.setFormaPagoContadoId(valor(formaPagoContadoId, global.getFormaPagoContadoId()));
            resultado.setFormaPagoCredito(texto(formaPagoCredito, global.getFormaPagoCredito()));
            resultado.setFormaPagoCreditoId(valor(formaPagoCreditoId, global.getFormaPagoCreditoId()));
            resultado.setConsumidorFinalIdentificacion(
                    texto(consumidorFinalIdentificacion, global.getConsumidorFinalIdentificacion()));
            resultado.setSucursalCliente(valor(sucursalCliente, global.getSucursalCliente()));
            resultado.setCiudadPorDefecto(texto(ciudadPorDefecto, global.getCiudadPorDefecto()));
            resultado.setEnviarDian(valor(enviarDian, global.isEnviarDian()));
            resultado.setEnviarEmail(valor(enviarEmail, global.isEnviarEmail()));
            resultado.setMonedaLocal(texto(monedaLocal, global.getMonedaLocal()));
            resultado.setTrm(valor(trm, global.getTrm()));
            resultado.setUsarFechaActual(valor(usarFechaActual, global.isUsarFechaActual()));
            return resultado;
        }

        private static <T> T valor(T propio, T global) {
            return propio != null ? propio : global;
        }

        /** En YAML se suele dejar {@code ""} para "sin valor"; se trata igual que no definirlo. */
        private static String texto(String propio, String global) {
            return propio != null && !propio.isBlank() ? propio : global;
        }
    }

    @Data
    public static class HotelConfig {

        @NotBlank
        private String hotelKey;
        @NotBlank
        private String hotelName;
        private MiniHotelAuth minihotelAuth = new MiniHotelAuth();

        /** Credenciales de Siigo de este hotel. Si es null se usan las de integrations.siigo.auth. */
        private Credenciales siigoAuth;

        /**
         * Parámetros de facturación de este hotel. Lo que no se defina aquí se hereda de
         * integrations.siigo.facturacion.
         */
        private FacturacionHotel facturacion;

        private boolean enabled = true;

        @Data
        public static class MiniHotelAuth {
            @NotBlank
            private String username;
            @NotBlank
            private String password;
            @NotBlank
            private String hotelId;
            /**
             * Clave lógica del tenant de Siigo. Se guarda en la auditoría y permite
             * reintentar una transacción fallida recuperando el hotel correcto.
             */
            private String siigoTenant;
        }
    }
}
