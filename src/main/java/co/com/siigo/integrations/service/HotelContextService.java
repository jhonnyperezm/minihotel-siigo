package co.com.siigo.integrations.service;

import co.com.siigo.integrations.config.IntegrationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Contexto del hotel activo en un entorno multi-tenant.
 *
 * <p>Además del hotel, resuelve las credenciales de Siigo y los parámetros de facturación
 * aplicables al hilo actual, con respaldo en la configuración global cuando el hotel no
 * define los suyos. Esto es lo que permite que clientes y catálogos trabajen contra el
 * tenant correcto sin recibir el hotel por parámetro.
 *
 * <p>Uso obligatorio con try-finally:
 * <pre>
 * try {
 *     hotelContextService.setCurrentHotel(hotelKey);
 *     integrationService.syncFacturasASiigo(date, date);
 * } finally {
 *     hotelContextService.clearCurrentHotel();
 * }
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotelContextService {

    /** Clave usada para los catálogos y el token cuando no hay hotel en contexto. */
    public static final String TENANT_POR_DEFECTO = "default";

    private final IntegrationProperties properties;

    /**
     * ThreadLocal con el hotel activo por hilo de ejecución, para que varios procesos
     * puedan trabajar con hoteles distintos simultáneamente.
     */
    private final ThreadLocal<IntegrationProperties.HotelConfig> currentHotelContext = new ThreadLocal<>();

    /**
     * Establece el hotel activo para el hilo actual.
     *
     * @param hotelKey identificador único del hotel
     * @return true si se estableció correctamente, false si no se encontró el hotel
     */
    public boolean setCurrentHotel(String hotelKey) {
        Optional<IntegrationProperties.HotelConfig> hotel = findHotelByKey(hotelKey);

        if (hotel.isPresent()) {
            currentHotelContext.set(hotel.get());
            log.debug("Hotel establecido en contexto: {} ({})", hotel.get().getHotelName(), hotelKey);
            return true;
        }
        log.warn("No se encontró configuración para el hotel: {}", hotelKey);
        return false;
    }

    /**
     * Obtiene el hotel activo en el contexto actual.
     *
     * @throws IllegalStateException si no hay un hotel establecido
     */
    public IntegrationProperties.HotelConfig getCurrentHotel() {
        IntegrationProperties.HotelConfig hotel = currentHotelContext.get();
        if (hotel == null) {
            throw new IllegalStateException("No hay un hotel establecido en el contexto actual. " +
                    "Debe llamar a setCurrentHotel() antes de usar este método.");
        }
        return hotel;
    }

    /**
     * Limpia el contexto del hilo. Debe llamarse siempre en un bloque finally
     * para evitar fugas de contexto entre peticiones.
     */
    public void clearCurrentHotel() {
        currentHotelContext.remove();
        log.debug("Contexto de hotel limpiado");
    }

    /**
     * Ejecuta la acción con el hotel indicado en el contexto y lo limpia al terminar.
     *
     * <p>Si no se indica {@code hotelKey} y solo hay un hotel activo, se usa ese. Con varios
     * hoteles activos es obligatorio indicarlo: adivinar significaría consultar o facturar
     * en la empresa equivocada.
     *
     * @throws IllegalArgumentException si el hotel no existe o no se puede determinar
     */
    public <T> T ejecutarEnHotel(String hotelKey, Supplier<T> accion) {
        String clave = resolverHotelKey(hotelKey);
        try {
            setCurrentHotel(clave);
            return accion.get();
        } finally {
            clearCurrentHotel();
        }
    }

    private String resolverHotelKey(String hotelKey) {
        if (hotelKey != null && !hotelKey.isBlank()) {
            return findHotelByKey(hotelKey)
                    .map(IntegrationProperties.HotelConfig::getHotelKey)
                    .orElseThrow(() -> new IllegalArgumentException("Hotel no configurado: " + hotelKey));
        }
        var activos = getActiveHotels();
        if (activos.size() == 1) {
            return activos.get(0).getHotelKey();
        }
        throw new IllegalArgumentException("Hay " + activos.size()
                + " hoteles activos: indique el parámetro hotelKey");
    }

    public boolean hasCurrentHotel() {
        return currentHotelContext.get() != null;
    }

    /**
     * Clave del tenant de Siigo del hilo actual. Se usa para segmentar el token y los catálogos.
     */
    public String getTenantKey() {
        if (!hasCurrentHotel()) return TENANT_POR_DEFECTO;
        var hotel = currentHotelContext.get();
        var tenant = hotel.getMinihotelAuth() != null ? hotel.getMinihotelAuth().getSiigoTenant() : null;
        return tenant != null && !tenant.isBlank() ? tenant : hotel.getHotelKey();
    }

    /**
     * Credenciales de Siigo del hotel activo, con respaldo en las globales.
     *
     * @throws IllegalStateException si no hay credenciales configuradas
     */
    public IntegrationProperties.Credenciales getCredencialesSiigo() {
        IntegrationProperties.Credenciales credenciales = null;

        if (hasCurrentHotel()) {
            credenciales = currentHotelContext.get().getSiigoAuth();
        }
        if (credenciales == null || !credenciales.estanCompletas()) {
            credenciales = properties.getSiigo().getAuth();
        }
        if (credenciales == null || !credenciales.estanCompletas()) {
            throw new IllegalStateException("No hay credenciales de Siigo configuradas para el tenant '"
                    + getTenantKey() + "'. Defina integrations.siigo.auth o el bloque siigo-auth del hotel.");
        }
        return credenciales;
    }

    /**
     * Parámetros de facturación del hotel activo. Cada parámetro que el hotel no define se
     * toma de la configuración global.
     */
    public IntegrationProperties.Facturacion getFacturacion() {
        var global = properties.getSiigo().getFacturacion();
        if (hasCurrentHotel() && currentHotelContext.get().getFacturacion() != null) {
            return currentHotelContext.get().getFacturacion().combinarCon(global);
        }
        return global;
    }

    public Optional<IntegrationProperties.HotelConfig> findHotelByKey(String hotelKey) {
        if (hotelKey == null) return Optional.empty();
        return properties.getHotels().stream()
                .filter(h -> hotelKey.equals(h.getHotelKey()))
                .findFirst();
    }

    /**
     * Busca un hotel por la clave de tenant de Siigo, cayendo en el {@code hotel-key}
     * cuando el tenant no está definido explícitamente.
     */
    public Optional<IntegrationProperties.HotelConfig> findHotelByTenant(String siigoTenant) {
        if (siigoTenant == null || siigoTenant.isBlank()) return Optional.empty();
        return properties.getHotels().stream()
                .filter(h -> siigoTenant.equalsIgnoreCase(tenantDe(h)))
                .findFirst();
    }

    /**
     * Establece el hotel activo buscándolo por su tenant de Siigo.
     * Es el camino que usa el reintento de una transacción fallida.
     */
    public boolean setCurrentHotelByTenant(String siigoTenant) {
        Optional<IntegrationProperties.HotelConfig> hotel = findHotelByTenant(siigoTenant);
        if (hotel.isPresent()) {
            currentHotelContext.set(hotel.get());
            log.debug("Hotel establecido por tenant '{}': {}", siigoTenant, hotel.get().getHotelName());
            return true;
        }
        log.warn("No se encontró configuración para el tenant de Siigo: {}", siigoTenant);
        return false;
    }

    /**
     * Busca un hotel por su código de propiedad en MiniHotel ({@code hotelCode} del webhook,
     * que es el mismo {@code hotel-id} de la configuración).
     *
     * <p>Es el enrutador multi-tenant de los webhooks: el evento llega identificado por hotel
     * de MiniHotel, y de ahí hay que llegar a las credenciales de Siigo correctas.
     */
    public Optional<IntegrationProperties.HotelConfig> findHotelByMinihotelId(String minihotelHotelId) {
        if (minihotelHotelId == null || minihotelHotelId.isBlank()) return Optional.empty();
        return properties.getHotels().stream()
                .filter(h -> h.getMinihotelAuth() != null
                        && minihotelHotelId.equalsIgnoreCase(h.getMinihotelAuth().getHotelId()))
                .findFirst();
    }

    /**
     * Establece el hotel activo a partir del código de propiedad de MiniHotel.
     *
     * @return true si se encontró el hotel, false si el {@code hotelCode} no está configurado
     */
    public boolean setCurrentHotelByMinihotelId(String minihotelHotelId) {
        Optional<IntegrationProperties.HotelConfig> hotel = findHotelByMinihotelId(minihotelHotelId);
        if (hotel.isPresent()) {
            currentHotelContext.set(hotel.get());
            log.debug("Hotel establecido por hotelCode '{}': {}", minihotelHotelId, hotel.get().getHotelName());
            return true;
        }
        log.warn("No hay ningún hotel configurado con hotel-id '{}'", minihotelHotelId);
        return false;
    }

    public List<IntegrationProperties.HotelConfig> getAllHotels() {
        return properties.getHotels();
    }

    public List<IntegrationProperties.HotelConfig> getActiveHotels() {
        return properties.getHotels().stream()
                .filter(IntegrationProperties.HotelConfig::isEnabled)
                .toList();
    }

    private String tenantDe(IntegrationProperties.HotelConfig hotel) {
        var tenant = hotel.getMinihotelAuth() != null ? hotel.getMinihotelAuth().getSiigoTenant() : null;
        return tenant != null && !tenant.isBlank() ? tenant : hotel.getHotelKey();
    }
}
