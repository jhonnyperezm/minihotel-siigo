package co.com.siigo.integrations.client;

import co.com.siigo.integrations.dto.siigo.ClienteResponse;
import co.com.siigo.integrations.dto.siigo.CrearClienteRequest;
import co.com.siigo.integrations.dto.siigo.PaginatedResponse;
import co.com.siigo.integrations.security.SiigoTokenStore;
import co.com.siigo.integrations.security.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;

/**
 * Cliente REST de terceros de Siigo (endpoint {@code /v1/customers}).
 *
 * <p>Equivale a {@code WorldOfficeTerceroClient}, con una simplificación notable: en Siigo
 * no hace falta consultar catálogos de tipos de contribuyente, responsabilidades fiscales
 * ni ciudades por id. El tercero se crea con códigos fijos (13, 31, 41…) y códigos DANE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiigoClienteClient {

    private final RestTemplate siigoRestTemplate;
    private final SiigoTokenStore tokenStore;

    /**
     * Busca un tercero por su número de identificación.
     *
     * <p>Siigo no expone una búsqueda directa por identificación, sino el listado
     * {@code /v1/customers} con filtro; por eso se toma el primer resultado.
     *
     * @return el tercero si existe, vacío si no está registrado
     */
    public Optional<ClienteResponse> buscarPorIdentificacion(String identificacion) {
        if (identificacion == null || identificacion.isBlank()) return Optional.empty();

        try {
            var uri = UriComponentsBuilder.fromPath("/v1/customers")
                    .queryParam("identification", identificacion.trim())
                    .queryParam("page", 1)
                    .queryParam("page_size", 25)
                    .build()
                    .toUriString();

            var response = siigoRestTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    new ParameterizedTypeReference<PaginatedResponse<ClienteResponse>>() {
                    }
            );

            PaginatedResponse<ClienteResponse> body = response.getBody();
            if (body == null) return Optional.empty();

            // El filtro de Siigo puede devolver coincidencias parciales: se exige igualdad exacta.
            return body.resultsOrEmpty().stream()
                    .filter(c -> identificacion.trim().equalsIgnoreCase(c.identification()))
                    .findFirst();

        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            tokenStore.invalidate();
            throw new UnauthorizedException("No autorizado consultando terceros en Siigo", ex);
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("buscarClientePorIdentificacion", ex);
        }
    }

    /**
     * Consulta un tercero por su GUID.
     */
    public Optional<ClienteResponse> obtenerPorId(String clienteId) {
        try {
            var response = siigoRestTemplate.exchange(
                    "/v1/customers/{id}",
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    ClienteResponse.class,
                    clienteId
            );
            return Optional.ofNullable(response.getBody());
        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("obtenerClientePorId", ex);
        }
    }

    /**
     * Crea un tercero en Siigo.
     *
     * @throws IllegalStateException si Siigo rechaza la creación
     */
    public ClienteResponse crearCliente(CrearClienteRequest request) {
        try {
            var response = siigoRestTemplate.exchange(
                    "/v1/customers",
                    HttpMethod.POST,
                    new HttpEntity<>(request, tokenStore.headers()),
                    ClienteResponse.class
            );
            return response.getBody();

        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            tokenStore.invalidate();
            throw new UnauthorizedException("No autorizado creando tercero en Siigo", ex);
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("crearCliente", ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Error de comunicación al crear el tercero en Siigo", ex);
        }
    }

    /**
     * Lista terceros por página. Se usa desde el controlador de catálogos para diagnóstico.
     */
    public List<ClienteResponse> listarClientes(int pagina, int tamanoPagina) {
        try {
            var uri = UriComponentsBuilder.fromPath("/v1/customers")
                    .queryParam("page", pagina)
                    .queryParam("page_size", tamanoPagina)
                    .build()
                    .toUriString();

            var response = siigoRestTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    new ParameterizedTypeReference<PaginatedResponse<ClienteResponse>>() {
                    }
            );

            PaginatedResponse<ClienteResponse> body = response.getBody();
            return body == null ? List.of() : body.resultsOrEmpty();

        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("listarClientes", ex);
        }
    }
}
