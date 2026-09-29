package co.com.siigo.integrations.client;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.siigo.AuthRequest;
import co.com.siigo.integrations.dto.siigo.AuthResponse;
import co.com.siigo.integrations.security.UnauthorizedException;
import co.com.siigo.integrations.util.Util;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Cliente de autenticación de Siigo API.
 *
 * <p>Siigo usa un esquema OAuth simplificado: se envían {@code username} y {@code access_key}
 * a {@code POST /auth} y se recibe un JWT con vigencia de 24 horas. Las credenciales se
 * generan en Siigo Nube (Alianzas &gt; Mi Credencial API).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiigoAuthClient {

    private final RestTemplate siigoRestTemplate;
    private final IntegrationProperties props;

    /**
     * Solicita un nuevo token de acceso.
     *
     * @param credenciales usuario, clave de acceso y Partner-Id del tenant
     * @return respuesta con el token y su vigencia en segundos
     * @throws UnauthorizedException si Siigo rechaza las credenciales
     * @throws IllegalStateException si la respuesta no trae token
     */
    public AuthResponse signIn(IntegrationProperties.Credenciales credenciales) {
        if (credenciales == null || !credenciales.estanCompletas()) {
            throw new IllegalStateException("Credenciales de Siigo incompletas: se requiere username y access-key");
        }

        try {
            var request = new AuthRequest(credenciales.getUsername(), credenciales.getAccessKey());
            var entity = new HttpEntity<>(request, Util.siigoAuthHeaders(credenciales.getPartnerId()));

            var response = siigoRestTemplate.exchange(
                    props.getSiigo().getAuthPath(),
                    HttpMethod.POST,
                    entity,
                    AuthResponse.class
            );

            AuthResponse body = response.getBody();
            if (body == null || body.accessToken() == null || body.accessToken().isBlank()) {
                throw new IllegalStateException("Siigo no devolvió token de acceso para el usuario: "
                        + credenciales.getUsername());
            }

            log.info("Token de Siigo obtenido para '{}' (vigencia {} s)",
                    credenciales.getUsername(), body.expiresIn());
            return body;

        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new UnauthorizedException(
                    "Credenciales de Siigo inválidas para el usuario: " + credenciales.getUsername(), ex);
        } catch (HttpClientErrorException ex) {
            throw new IllegalStateException("Siigo rechazó la autenticación (" + ex.getStatusCode() + "): "
                    + ex.getResponseBodyAsString(), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Error de comunicación al autenticar contra Siigo", ex);
        }
    }
}
