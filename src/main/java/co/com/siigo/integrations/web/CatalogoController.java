package co.com.siigo.integrations.web;

import co.com.siigo.integrations.dto.siigo.BodegaResponse;
import co.com.siigo.integrations.dto.siigo.CentroCostoResponse;
import co.com.siigo.integrations.dto.siigo.FormaPagoResponse;
import co.com.siigo.integrations.dto.siigo.ImpuestoResponse;
import co.com.siigo.integrations.dto.siigo.ProductoResponse;
import co.com.siigo.integrations.dto.siigo.TipoComprobanteResponse;
import co.com.siigo.integrations.dto.siigo.UsuarioResponse;
import co.com.siigo.integrations.service.HotelContextService;
import co.com.siigo.integrations.service.SiigoCatalogoService;
import co.com.siigo.integrations.util.CiudadSiigo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Consulta de los catálogos de Siigo del hotel indicado.
 *
 * <p>Es la herramienta principal para poner a punto la configuración: permite descubrir los
 * ids reales de comprobante, medios de pago, impuestos, vendedores, bodegas y centros de
 * costo que deben ir en {@code application.yml}.
 *
 * <p>Todos los endpoints reciben el {@code hotelKey} porque en Siigo cada empresa tiene
 * catálogos propios.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/catalogo/{hotelKey}")
public class CatalogoController {

    private final SiigoCatalogoService catalogoService;
    private final HotelContextService hotelContextService;

    /** Comprobantes de factura de venta (FV) configurados en el tenant. */
    @GetMapping("/comprobantes")
    public ResponseEntity<List<TipoComprobanteResponse>> comprobantes(@PathVariable String hotelKey) {
        return ResponseEntity.ok(enContexto(hotelKey, catalogoService::tiposComprobante));
    }

    /** Medios de pago habilitados para facturas de venta. */
    @GetMapping("/formas-pago")
    public ResponseEntity<List<FormaPagoResponse>> formasPago(@PathVariable String hotelKey) {
        return ResponseEntity.ok(enContexto(hotelKey, catalogoService::formasPago));
    }

    /** Impuestos configurados en la empresa. */
    @GetMapping("/impuestos")
    public ResponseEntity<List<ImpuestoResponse>> impuestos(@PathVariable String hotelKey) {
        return ResponseEntity.ok(enContexto(hotelKey, catalogoService::impuestos));
    }

    /** Usuarios de la empresa; el id del vendedor sale de aquí. */
    @GetMapping("/usuarios")
    public ResponseEntity<List<UsuarioResponse>> usuarios(@PathVariable String hotelKey) {
        return ResponseEntity.ok(enContexto(hotelKey, catalogoService::usuarios));
    }

    @GetMapping("/centros-costo")
    public ResponseEntity<List<CentroCostoResponse>> centrosCosto(@PathVariable String hotelKey) {
        return ResponseEntity.ok(enContexto(hotelKey, catalogoService::centrosCosto));
    }

    @GetMapping("/bodegas")
    public ResponseEntity<List<BodegaResponse>> bodegas(@PathVariable String hotelKey) {
        return ResponseEntity.ok(enContexto(hotelKey, catalogoService::bodegas));
    }

    /** Verifica que un código de producto exista en Siigo antes de usarlo en la configuración. */
    @GetMapping("/productos/{codigo}")
    public ResponseEntity<ProductoResponse> producto(@PathVariable String hotelKey,
                                                     @PathVariable String codigo) {
        ProductoResponse producto = enContexto(hotelKey,
                () -> catalogoService.buscarProductoPorCodigo(codigo).orElse(null));

        if (producto == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No existe un producto con código '" + codigo + "' en Siigo");
        }
        return ResponseEntity.ok(producto);
    }

    /**
     * Resumen de lo que la integración resolvió para este hotel.
     * Útil para confirmar de un vistazo que la configuración quedó bien.
     */
    @GetMapping("/resumen")
    public ResponseEntity<Map<String, Object>> resumen(@PathVariable String hotelKey) {
        return ResponseEntity.ok(enContexto(hotelKey, () -> Map.<String, Object>of(
                "tenant", hotelContextService.getTenantKey(),
                "tipoComprobanteId", catalogoService.obtenerTipoComprobanteId(),
                "vendedorId", catalogoService.obtenerVendedorId(),
                "impuestoIva", catalogoService.obtenerImpuestoIva()
                        .map(i -> i.name() + " (" + i.porcentajeSeguro() + "%, id " + i.id() + ")")
                        .orElse("sin IVA configurado"),
                "centroCostoId", catalogoService.obtenerCentroCostoId().map(String::valueOf).orElse("no aplica"),
                "productoNacional", hotelContextService.getFacturacion().getProductoNacional(),
                "productoExtranjero", hotelContextService.getFacturacion().getProductoExtranjero(),
                "enviarDian", hotelContextService.getFacturacion().isEnviarDian()
        )));
    }

    /** Invalida el caché de catálogos del hotel para forzar una recarga desde Siigo. */
    @PostMapping("/refrescar")
    public ResponseEntity<Map<String, String>> refrescar(@PathVariable String hotelKey) {
        enContexto(hotelKey, () -> {
            catalogoService.refrescar();
            return null;
        });
        return ResponseEntity.ok(Map.of("mensaje", "Catálogos del hotel '" + hotelKey + "' invalidados"));
    }

    /**
     * Ciudades reconocidas por la integración, con sus códigos DANE.
     * Siigo no expone un catálogo de ciudades, por eso la tabla es local.
     */
    @GetMapping("/ciudades")
    public ResponseEntity<List<Map<String, String>>> ciudades(@RequestParam(required = false) String nombre) {
        var ciudades = Arrays.stream(CiudadSiigo.values())
                .filter(c -> nombre == null || nombre.isBlank()
                        || c.getNombreOficial().toLowerCase().contains(nombre.toLowerCase()))
                .map(c -> Map.of(
                        "nombre", c.getNombreOficial(),
                        "countryCode", c.getCountryCode(),
                        "stateCode", c.getStateCode(),
                        "cityCode", c.getCityCode()))
                .toList();
        return ResponseEntity.ok(ciudades);
    }

    /**
     * Ejecuta la consulta con el hotel establecido en el contexto y lo limpia después.
     */
    private <T> T enContexto(String hotelKey, Supplier<T> accion) {
        try {
            if (!hotelContextService.setCurrentHotel(hotelKey)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Hotel no encontrado: " + hotelKey);
            }
            return accion.get();
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }
}
