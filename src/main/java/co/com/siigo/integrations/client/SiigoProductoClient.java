package co.com.siigo.integrations.client;

import co.com.siigo.integrations.dto.siigo.PaginatedResponse;
import co.com.siigo.integrations.dto.siigo.ProductoResponse;
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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cliente REST del catálogo de productos y servicios de Siigo.
 * Reemplaza a {@code WorldOfficeInventarioClient}.
 *
 * <p>En la factura los renglones referencian el producto por su {@code code}, no por id,
 * de modo que este cliente se usa sobre todo para validar que el código exista y para
 * saber si el producto maneja inventario (y por tanto admite bodega).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiigoProductoClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGINAS = 20;

    private final RestTemplate siigoRestTemplate;
    private final SiigoTokenStore tokenStore;

    /**
     * Busca un producto por su código exacto.
     */
    public Optional<ProductoResponse> buscarPorCodigo(String codigo) {
        if (codigo == null || codigo.isBlank()) return Optional.empty();

        try {
            var uri = UriComponentsBuilder.fromPath("/v1/products")
                    .queryParam("code", codigo.trim())
                    .queryParam("page", 1)
                    .queryParam("page_size", 25)
                    .build()
                    .toUriString();

            var response = siigoRestTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    new ParameterizedTypeReference<PaginatedResponse<ProductoResponse>>() {
                    }
            );

            PaginatedResponse<ProductoResponse> body = response.getBody();
            if (body == null) return Optional.empty();

            return body.resultsOrEmpty().stream()
                    .filter(p -> codigo.trim().equalsIgnoreCase(p.code()))
                    .findFirst();

        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            tokenStore.invalidate();
            throw new UnauthorizedException("No autorizado consultando productos en Siigo", ex);
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("buscarProductoPorCodigo", ex);
        }
    }

    /**
     * Lista todos los productos recorriendo las páginas disponibles.
     */
    public List<ProductoResponse> listarProductos() {
        List<ProductoResponse> acumulado = new ArrayList<>();

        for (int pagina = 1; pagina <= MAX_PAGINAS; pagina++) {
            var lote = listarPagina(pagina);
            acumulado.addAll(lote);
            if (lote.size() < PAGE_SIZE) break;
        }
        return acumulado;
    }

    private List<ProductoResponse> listarPagina(int pagina) {
        try {
            var uri = UriComponentsBuilder.fromPath("/v1/products")
                    .queryParam("page", pagina)
                    .queryParam("page_size", PAGE_SIZE)
                    .build()
                    .toUriString();

            var response = siigoRestTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    new ParameterizedTypeReference<PaginatedResponse<ProductoResponse>>() {
                    }
            );

            PaginatedResponse<ProductoResponse> body = response.getBody();
            return body == null ? List.of() : body.resultsOrEmpty();

        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            tokenStore.invalidate();
            throw new UnauthorizedException("No autorizado listando productos en Siigo", ex);
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("listarProductos", ex);
        }
    }
}
