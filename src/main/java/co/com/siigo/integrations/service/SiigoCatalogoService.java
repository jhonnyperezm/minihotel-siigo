package co.com.siigo.integrations.service;

import co.com.siigo.integrations.client.SiigoCatalogoClient;
import co.com.siigo.integrations.client.SiigoProductoClient;
import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.siigo.BodegaResponse;
import co.com.siigo.integrations.dto.siigo.CentroCostoResponse;
import co.com.siigo.integrations.dto.siigo.FormaPagoResponse;
import co.com.siigo.integrations.dto.siigo.ImpuestoResponse;
import co.com.siigo.integrations.dto.siigo.ProductoResponse;
import co.com.siigo.integrations.dto.siigo.TipoComprobanteResponse;
import co.com.siigo.integrations.dto.siigo.UsuarioResponse;
import co.com.siigo.integrations.dto.siigo.enums.DocumentoTipoEnum;
import co.com.siigo.integrations.util.Util;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catálogos de Siigo cacheados en memoria.
 *
 * <p>Reemplaza a {@code WorldOfficeCatalogoService}, con una diferencia estructural: como
 * cada empresa de Siigo es un tenant independiente, el caché está segmentado por tenant.
 * Dos hoteles de empresas distintas no comparten ids de comprobante, impuestos ni vendedores.
 *
 * <p>La carga es perezosa por defecto: la primera consulta de cada tenant dispara la
 * descarga. Con {@code integrations.siigo.precargar-catalogos=true} se precargan al arrancar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiigoCatalogoService {

    private final SiigoCatalogoClient catalogoClient;
    private final SiigoProductoClient productoClient;
    private final HotelContextService hotelContextService;
    private final IntegrationProperties props;

    private final Map<String, Catalogo> cachePorTenant = new ConcurrentHashMap<>();

    // ==================== INICIALIZACIÓN ====================

    @PostConstruct
    void preload() {
        if (!props.getSiigo().isPrecargarCatalogos()) {
            log.info("[CATALOGO] Precarga desactivada: los catálogos se cargarán bajo demanda por tenant");
            return;
        }

        for (IntegrationProperties.HotelConfig hotel : hotelContextService.getActiveHotels()) {
            try {
                hotelContextService.setCurrentHotel(hotel.getHotelKey());
                precargarTenant(hotel.getHotelName());
            } catch (Exception ex) {
                log.warn("[CATALOGO] No se pudo precargar el hotel '{}': {}", hotel.getHotelName(), ex.getMessage());
            } finally {
                hotelContextService.clearCurrentHotel();
            }
        }
    }

    private void precargarTenant(String nombreHotel) {
        log.info("[CATALOGO] Precargando catálogos de '{}'...", nombreHotel);
        cargar("tipos de comprobante", () -> tiposComprobante().size());
        cargar("formas de pago", () -> formasPago().size());
        cargar("impuestos", () -> impuestos().size());
        cargar("usuarios", () -> usuarios().size());
        cargar("centros de costo", () -> centrosCosto().size());
        cargar("bodegas", () -> bodegas().size());
        log.info("[CATALOGO] Precarga de '{}' finalizada", nombreHotel);
    }

    private void cargar(String nombre, java.util.function.Supplier<Integer> accion) {
        try {
            log.info("[CATALOGO] {} OK ({})", nombre, accion.get());
        } catch (Exception ex) {
            log.warn("[CATALOGO] {} FAIL: {}", nombre, ex.getMessage());
        }
    }

    // ==================== TIPOS DE COMPROBANTE ====================

    /**
     * Tipos de comprobante de factura de venta configurados en el tenant activo.
     */
    public List<TipoComprobanteResponse> tiposComprobante() {
        Catalogo c = catalogo();
        if (c.tiposComprobante.isEmpty()) {
            synchronized (c) {
                if (c.tiposComprobante.isEmpty()) {
                    c.tiposComprobante = List.copyOf(
                            catalogoClient.listarTiposComprobante(DocumentoTipoEnum.FACTURA_VENTA));
                }
            }
        }
        return c.tiposComprobante;
    }

    /**
     * Id del comprobante con el que se emitirán las facturas.
     *
     * <p>Orden de resolución: id explícito en configuración, código configurado,
     * primer comprobante electrónico activo, primer comprobante activo.
     *
     * @throws IllegalStateException si el tenant no tiene comprobantes de venta configurados
     */
    public Long obtenerTipoComprobanteId() {
        var facturacion = hotelContextService.getFacturacion();

        if (facturacion.getTipoComprobanteId() != null) {
            return facturacion.getTipoComprobanteId();
        }

        List<TipoComprobanteResponse> disponibles = tiposComprobante();
        String codigo = facturacion.getTipoComprobanteCodigo();

        if (codigo != null && !codigo.isBlank()) {
            return disponibles.stream()
                    .filter(t -> codigo.equalsIgnoreCase(t.code()))
                    .findFirst()
                    .map(TipoComprobanteResponse::id)
                    .orElseThrow(() -> new IllegalStateException(
                            "No existe un comprobante de venta con código '" + codigo + "' en Siigo. " +
                            "Comprobantes disponibles: " + describirComprobantes(disponibles)));
        }

        return disponibles.stream()
                .filter(TipoComprobanteResponse::estaActivo)
                .filter(TipoComprobanteResponse::esElectronico)
                .findFirst()
                .or(() -> disponibles.stream().filter(TipoComprobanteResponse::estaActivo).findFirst())
                .map(TipoComprobanteResponse::id)
                .orElseThrow(() -> new IllegalStateException(
                        "Siigo no devolvió comprobantes de venta (FV) para el tenant '"
                        + hotelContextService.getTenantKey() + "'."));
    }

    /**
     * Comprobante completo asociado al id resuelto, útil para saber si exige centro de costos.
     */
    public Optional<TipoComprobanteResponse> obtenerTipoComprobante() {
        Long id = obtenerTipoComprobanteId();
        return tiposComprobante().stream().filter(t -> id.equals(t.id())).findFirst();
    }

    // ==================== FORMAS DE PAGO ====================

    public List<FormaPagoResponse> formasPago() {
        Catalogo c = catalogo();
        if (c.formasPago.isEmpty()) {
            synchronized (c) {
                if (c.formasPago.isEmpty()) {
                    c.formasPago = List.copyOf(
                            catalogoClient.listarFormasPago(DocumentoTipoEnum.FACTURA_VENTA));
                }
            }
        }
        return c.formasPago;
    }

    /**
     * Busca un medio de pago por nombre, ignorando tildes y mayúsculas.
     * Si no lo encuentra, devuelve el primero activo para no bloquear la facturación.
     *
     * @throws IllegalStateException si el tenant no tiene medios de pago configurados
     */
    /**
     * Resuelve el medio de pago por id si está configurado; si no, por nombre.
     * <p>
     * Un id configurado que no existe en Siigo es un error de configuración: se falla en vez de
     * caer al primer medio activo, para no facturar con un medio de pago equivocado.
     */
    public FormaPagoResponse obtenerFormaPago(Long id, String nombre) {
        if (id == null) return obtenerFormaPago(nombre);

        return formasPago().stream()
                .filter(f -> id.equals(f.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "El medio de pago con id " + id + " no existe en Siigo para el tenant '"
                        + hotelContextService.getTenantKey() + "'. Revisa forma-pago-*-id."));
    }

    public FormaPagoResponse obtenerFormaPago(String nombre) {
        List<FormaPagoResponse> disponibles = formasPago();
        String buscado = Util.normalizarTexto(nombre);

        return disponibles.stream()
                .filter(FormaPagoResponse::estaActivo)
                .filter(f -> Util.normalizarTexto(f.name()).equalsIgnoreCase(buscado))
                .findFirst()
                .or(() -> {
                    log.warn("No se encontró el medio de pago '{}' en Siigo; se usará el primero activo", nombre);
                    return disponibles.stream().filter(FormaPagoResponse::estaActivo).findFirst();
                })
                .orElseThrow(() -> new IllegalStateException(
                        "Siigo no devolvió medios de pago para facturas de venta en el tenant '"
                        + hotelContextService.getTenantKey() + "'."));
    }

    // ==================== IMPUESTOS ====================

    public List<ImpuestoResponse> impuestos() {
        Catalogo c = catalogo();
        if (c.impuestos.isEmpty()) {
            synchronized (c) {
                if (c.impuestos.isEmpty()) {
                    c.impuestos = List.copyOf(catalogoClient.listarImpuestos());
                }
            }
        }
        return c.impuestos;
    }

    /**
     * Impuesto de IVA a aplicar a los renglones de huéspedes nacionales.
     * Se resuelve por id explícito, por nombre configurado o tomando el primer IVA activo.
     *
     * @return vacío si la empresa no tiene IVA configurado (se facturará sin impuesto)
     */
    public Optional<ImpuestoResponse> obtenerImpuestoIva() {
        var facturacion = hotelContextService.getFacturacion();
        List<ImpuestoResponse> disponibles = impuestos();

        if (facturacion.getImpuestoIvaId() != null) {
            return disponibles.stream()
                    .filter(i -> facturacion.getImpuestoIvaId().equals(i.id()))
                    .findFirst();
        }

        String nombre = facturacion.getImpuestoIva();
        if (nombre != null && !nombre.isBlank()) {
            String buscado = Util.normalizarTexto(nombre);
            Optional<ImpuestoResponse> porNombre = disponibles.stream()
                    .filter(ImpuestoResponse::estaActivo)
                    .filter(i -> Util.normalizarTexto(i.name()).equalsIgnoreCase(buscado))
                    .findFirst();
            if (porNombre.isPresent()) return porNombre;
            log.warn("No se encontró el impuesto '{}' en Siigo; se buscará el primer IVA activo", nombre);
        }

        return disponibles.stream()
                .filter(ImpuestoResponse::estaActivo)
                .filter(ImpuestoResponse::esIva)
                .findFirst();
    }

    // ==================== USUARIOS / VENDEDORES ====================

    public List<UsuarioResponse> usuarios() {
        Catalogo c = catalogo();
        if (c.usuarios.isEmpty()) {
            synchronized (c) {
                if (c.usuarios.isEmpty()) {
                    c.usuarios = List.copyOf(catalogoClient.listarUsuarios());
                }
            }
        }
        return c.usuarios;
    }

    /**
     * Id del vendedor a asociar a la factura.
     * Se resuelve por id explícito, por identificación / usuario / correo, o el primero activo.
     *
     * @throws IllegalStateException si el tenant no tiene usuarios activos
     */
    public Long obtenerVendedorId() {
        var facturacion = hotelContextService.getFacturacion();

        if (facturacion.getVendedorId() != null) {
            return facturacion.getVendedorId();
        }

        List<UsuarioResponse> disponibles = usuarios();
        String buscado = facturacion.getVendedorIdentificacion();

        if (buscado != null && !buscado.isBlank()) {
            Optional<UsuarioResponse> encontrado = disponibles.stream()
                    .filter(u -> buscado.equalsIgnoreCase(u.identification())
                            || buscado.equalsIgnoreCase(u.username())
                            || buscado.equalsIgnoreCase(u.email()))
                    .findFirst();
            if (encontrado.isPresent()) return encontrado.get().id();
            log.warn("No se encontró el vendedor '{}' en Siigo; se usará el primer usuario activo", buscado);
        }

        return disponibles.stream()
                .filter(UsuarioResponse::estaActivo)
                .findFirst()
                .map(UsuarioResponse::id)
                .orElseThrow(() -> new IllegalStateException(
                        "Siigo no devolvió usuarios para el tenant '" + hotelContextService.getTenantKey()
                        + "'. No se puede asignar el vendedor de la factura."));
    }

    // ==================== CENTROS DE COSTO Y BODEGAS ====================

    public List<CentroCostoResponse> centrosCosto() {
        Catalogo c = catalogo();
        if (c.centrosCosto.isEmpty()) {
            synchronized (c) {
                if (c.centrosCosto.isEmpty()) {
                    c.centrosCosto = List.copyOf(catalogoClient.listarCentrosCosto());
                }
            }
        }
        return c.centrosCosto;
    }

    public List<BodegaResponse> bodegas() {
        Catalogo c = catalogo();
        if (c.bodegas.isEmpty()) {
            synchronized (c) {
                if (c.bodegas.isEmpty()) {
                    c.bodegas = List.copyOf(catalogoClient.listarBodegas());
                }
            }
        }
        return c.bodegas;
    }

    /**
     * Valida que el centro de costos configurado exista y esté activo.
     *
     * @return el id si es válido, vacío si no está configurado o no existe
     */
    public Optional<Long> obtenerCentroCostoId() {
        Long configurado = hotelContextService.getFacturacion().getCentroCostoId();
        if (configurado == null) return Optional.empty();

        boolean existe = centrosCosto().stream()
                .anyMatch(cc -> configurado.equals(cc.id()) && cc.estaActivo());

        if (!existe) {
            log.warn("El centro de costos {} no existe o está inactivo en Siigo; se omitirá en la factura",
                    configurado);
            return Optional.empty();
        }
        return Optional.of(configurado);
    }

    // ==================== PRODUCTOS ====================

    /**
     * Busca un producto por código, cacheando el resultado por tenant.
     *
     * @throws IllegalStateException si el producto no existe en Siigo
     */
    public ProductoResponse obtenerProductoPorCodigo(String codigo) {
        Catalogo c = catalogo();
        ProductoResponse enCache = c.productos.get(codigo);
        if (enCache != null) return enCache;

        ProductoResponse encontrado = productoClient.buscarPorCodigo(codigo)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe un producto con código '" + codigo + "' en Siigo. " +
                        "Créalo en Siigo Nube o ajusta integrations.siigo.facturacion.producto-*"));

        c.productos.put(codigo, encontrado);
        return encontrado;
    }

    public Optional<ProductoResponse> buscarProductoPorCodigo(String codigo) {
        try {
            return Optional.of(obtenerProductoPorCodigo(codigo));
        } catch (IllegalStateException ex) {
            return Optional.empty();
        }
    }

    /**
     * Bodega a enviar en un renglón. Solo se devuelve si el producto maneja inventario
     * y la bodega configurada existe y está activa; Siigo rechaza la factura en caso contrario.
     */
    public Optional<Long> obtenerBodegaPara(ProductoResponse producto) {
        Long configurada = hotelContextService.getFacturacion().getBodegaId();
        if (configurada == null || producto == null || !producto.manejaInventario()) {
            return Optional.empty();
        }

        boolean existe = bodegas().stream()
                .anyMatch(b -> configurada.equals(b.id()) && b.estaActiva());

        if (!existe) {
            log.warn("La bodega {} no existe o está inactiva en Siigo; se omitirá en el renglón", configurada);
            return Optional.empty();
        }
        return Optional.of(configurada);
    }

    // ==================== MANTENIMIENTO ====================

    /**
     * Vacía el caché del tenant activo para forzar una recarga desde Siigo.
     */
    public void refrescar() {
        String tenant = hotelContextService.getTenantKey();
        cachePorTenant.remove(tenant);
        log.info("[CATALOGO] Caché del tenant '{}' invalidado", tenant);
    }

    /** Vacía el caché de todos los tenants. */
    public void refrescarTodo() {
        cachePorTenant.clear();
        log.info("[CATALOGO] Caché de todos los tenants invalidado");
    }

    private Catalogo catalogo() {
        return cachePorTenant.computeIfAbsent(hotelContextService.getTenantKey(), k -> new Catalogo());
    }

    private String describirComprobantes(List<TipoComprobanteResponse> disponibles) {
        return disponibles.stream()
                .map(t -> t.code() + " (" + t.name() + ", id=" + t.id() + ")")
                .reduce((a, b) -> a + ", " + b)
                .orElse("ninguno");
    }

    /**
     * Catálogos de un tenant. Los campos son volatile porque se publican tras la carga
     * inicial y se leen sin bloqueo desde varios hilos.
     */
    private static final class Catalogo {
        private volatile List<TipoComprobanteResponse> tiposComprobante = List.of();
        private volatile List<FormaPagoResponse> formasPago = List.of();
        private volatile List<ImpuestoResponse> impuestos = List.of();
        private volatile List<UsuarioResponse> usuarios = List.of();
        private volatile List<CentroCostoResponse> centrosCosto = List.of();
        private volatile List<BodegaResponse> bodegas = List.of();
        private final Map<String, ProductoResponse> productos = new ConcurrentHashMap<>();
    }
}
