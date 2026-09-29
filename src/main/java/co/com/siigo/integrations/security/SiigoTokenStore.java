package co.com.siigo.integrations.security;

import co.com.siigo.integrations.client.SiigoAuthClient;
import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.service.HotelContextService;
import co.com.siigo.integrations.util.Util;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caché de tokens de Siigo, segmentada por tenant.
 *
 * <p>En World Office bastaba un token estático para toda la licencia. En Siigo cada empresa
 * tiene sus propias credenciales, así que se guarda un token por tenant y se renueva solo
 * cuando está por expirar (24 h de vigencia, menos el margen configurado).
 *
 * <p>Es thread-safe: cada hilo resuelve su tenant desde {@link HotelContextService} y la
 * renovación se sincroniza por tenant, de modo que dos hilos del mismo hotel no piden
 * dos tokens en paralelo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiigoTokenStore {

    private final SiigoAuthClient authClient;
    private final HotelContextService hotelContextService;
    private final IntegrationProperties props;

    private final Map<String, TokenInfo> cache = new ConcurrentHashMap<>();

    /**
     * Token vigente del tenant activo, renovándolo si hace falta.
     */
    public String get() {
        String tenant = hotelContextService.getTenantKey();
        long margen = props.getSiigo().getTokenRefreshMarginSeconds();

        TokenInfo actual = cache.get(tenant);
        if (actual != null && actual.vigente(margen)) {
            return actual.token();
        }
        return renovar(tenant);
    }

    /**
     * Headers listos para cualquier llamada a Siigo: Bearer token + Partner-Id + JSON.
     */
    public HttpHeaders headers() {
        return Util.siigoHeaders(get(), partnerIdActual());
    }

    /**
     * Descarta el token del tenant activo. Se invoca cuando Siigo responde 401,
     * para forzar una nueva autenticación en el siguiente intento.
     */
    public void invalidate() {
        String tenant = hotelContextService.getTenantKey();
        if (cache.remove(tenant) != null) {
            log.warn("Token de Siigo invalidado para el tenant '{}'", tenant);
        }
    }

    /** Descarta todos los tokens en caché. */
    public void invalidateAll() {
        cache.clear();
        log.warn("Todos los tokens de Siigo fueron invalidados");
    }

    /** Indica si el tenant activo ya tiene un token vigente en caché. */
    public boolean tieneTokenVigente() {
        TokenInfo actual = cache.get(hotelContextService.getTenantKey());
        return actual != null && actual.vigente(props.getSiigo().getTokenRefreshMarginSeconds());
    }

    private synchronized String renovar(String tenant) {
        // Otro hilo pudo renovarlo mientras esperábamos el lock
        TokenInfo actual = cache.get(tenant);
        long margen = props.getSiigo().getTokenRefreshMarginSeconds();
        if (actual != null && actual.vigente(margen)) {
            return actual.token();
        }

        log.info("Solicitando token de Siigo para el tenant '{}'", tenant);
        var credenciales = hotelContextService.getCredencialesSiigo();
        var response = authClient.signIn(credenciales);

        long vigencia = response.expiresIn() != null ? response.expiresIn() : 86400L;
        var nuevo = new TokenInfo(response.accessToken(), Instant.now().plusSeconds(vigencia));
        cache.put(tenant, nuevo);
        return nuevo.token();
    }

    private String partnerIdActual() {
        var credenciales = hotelContextService.getCredencialesSiigo();
        return credenciales.getPartnerId();
    }

    /**
     * Token y su instante de expiración.
     */
    private record TokenInfo(String token, Instant expiresAt) {

        boolean vigente(long margenSegundos) {
            return token != null
                    && !token.isBlank()
                    && Instant.now().isBefore(expiresAt.minusSeconds(margenSegundos));
        }
    }
}
