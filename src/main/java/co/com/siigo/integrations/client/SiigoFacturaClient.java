package co.com.siigo.integrations.client;

import co.com.siigo.integrations.dto.siigo.CrearFacturaRequest;
import co.com.siigo.integrations.dto.siigo.EnvioCorreoRequest;
import co.com.siigo.integrations.dto.siigo.EnvioDianResponse;
import co.com.siigo.integrations.dto.siigo.FacturaResponse;
import co.com.siigo.integrations.dto.siigo.PaginatedResponse;
import co.com.siigo.integrations.security.SiigoTokenStore;
import co.com.siigo.integrations.security.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Cliente REST de facturas de venta de Siigo.
 *
 * <p>Reemplaza a {@code WorldOfficeDocumentoClient}. Dos diferencias importantes:
 * <ul>
 *   <li>No existe el paso de "contabilizar": Siigo contabiliza el documento al crearlo.</li>
 *   <li>El envío a la DIAN se hace con {@code stamp.send=true} en la creación, o después
 *       con {@code POST /v1/invoices/{id}/stamp}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiigoFacturaClient {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGINAS = 50;

    private final RestTemplate siigoRestTemplate;
    private final SiigoTokenStore tokenStore;

    /**
     * Crea una factura de venta.
     *
     * @return la factura creada, con su GUID y estado ante la DIAN
     * @throws UnauthorizedException si el token no es válido
     * @throws IllegalStateException si Siigo rechaza el documento
     */
    public Optional<FacturaResponse> crearFactura(CrearFacturaRequest request) {
        try {
            var response = siigoRestTemplate.exchange(
                    "/v1/invoices",
                    HttpMethod.POST,
                    new HttpEntity<>(request, tokenStore.headers()),
                    FacturaResponse.class
            );
            return ofNullable(response.getBody());

        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            tokenStore.invalidate();
            throw new UnauthorizedException("No autorizado creando factura de venta en Siigo", ex);
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("crearFactura", ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("Error de comunicación al crear la factura en Siigo", ex);
        }
    }

    /**
     * Consulta una factura por su GUID.
     */
    public Optional<FacturaResponse> obtenerFactura(String facturaId) {
        try {
            var response = siigoRestTemplate.exchange(
                    "/v1/invoices/{id}",
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    FacturaResponse.class,
                    facturaId
            );
            return ofNullable(response.getBody());

        } catch (HttpClientErrorException.NotFound ex) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            tokenStore.invalidate();
            throw new UnauthorizedException("No autorizado consultando factura en Siigo", ex);
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("obtenerFactura", ex);
        }
    }

    /**
     * Lista las facturas de un rango de fechas, recorriendo todas las páginas.
     *
     * @param fechaInicio fecha inicial en formato yyyy-MM-dd
     * @param fechaFin    fecha final en formato yyyy-MM-dd
     * @param documentId  id del tipo de comprobante a filtrar; null para todos
     */
    public List<FacturaResponse> listarFacturas(String fechaInicio, String fechaFin, Long documentId) {
        List<FacturaResponse> acumulado = new ArrayList<>();

        for (int pagina = 1; pagina <= MAX_PAGINAS; pagina++) {
            var lote = listarPagina(fechaInicio, fechaFin, documentId, pagina);
            acumulado.addAll(lote);
            if (lote.size() < PAGE_SIZE) break;
        }
        return acumulado;
    }

    /**
     * Envía a la DIAN una factura electrónica ya creada.
     * Útil cuando se generó con {@code stamp.send=false} o cuando la DIAN la rechazó.
     *
     * @return true si la DIAN la aceptó
     */
    public boolean enviarADian(String facturaId) {
        try {
            log.info("Enviando factura {} a la DIAN", facturaId);
            var response = siigoRestTemplate.exchange(
                    "/v1/invoices/{id}/stamp",
                    HttpMethod.POST,
                    new HttpEntity<>(tokenStore.headers()),
                    EnvioDianResponse.class,
                    facturaId
            );

            EnvioDianResponse body = response.getBody();
            boolean aceptada = body != null && body.aceptada();
            if (aceptada) {
                log.info("Factura {} aceptada por la DIAN. CUFE: {}", facturaId, body.cufe());
            } else {
                log.warn("Factura {} no fue aceptada por la DIAN: {}", facturaId,
                        body == null ? "sin respuesta" : body.status() + " " + body.errors());
            }
            return aceptada;

        } catch (Exception ex) {
            log.warn("Error al enviar la factura {} a la DIAN: {}", facturaId, ex.getMessage());
            return false;
        }
    }

    /**
     * Envío a la DIAN sin esperar la respuesta.
     */
    @Async("siigoAsyncExecutor")
    public void enviarADianAsync(String facturaId) {
        enviarADian(facturaId);
    }

    /**
     * Envía la factura por correo al cliente registrado en Siigo, sin esperar la respuesta.
     */
    @Async("siigoAsyncExecutor")
    public void enviarPorCorreoAsync(String facturaId) {
        try {
            log.info("Enviando por correo la factura {}", facturaId);
            siigoRestTemplate.exchange(
                    "/v1/invoices/{id}/mail",
                    HttpMethod.POST,
                    new HttpEntity<>(EnvioCorreoRequest.porDefecto(), tokenStore.headers()),
                    Void.class,
                    facturaId
            );
            log.info("Factura {} enviada por correo", facturaId);
        } catch (Exception ex) {
            log.warn("Error al enviar por correo la factura {}: {}", facturaId, ex.getMessage());
        }
    }

    /**
     * Consulta el detalle de los errores de una factura electrónica rechazada por la DIAN.
     *
     * @return el cuerpo de la respuesta, o un texto descriptivo si no se pudo consultar
     */
    public String consultarErroresDian(String facturaId) {
        try {
            var response = siigoRestTemplate.exchange(
                    "/v1/invoices/{id}/stamp/errors",
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    String.class,
                    facturaId
            );
            return response.getBody();
        } catch (HttpStatusCodeException ex) {
            return SiigoErrores.describir(ex);
        } catch (Exception ex) {
            return "No fue posible consultar los errores DIAN: " + ex.getMessage();
        }
    }

    private List<FacturaResponse> listarPagina(String fechaInicio, String fechaFin, Long documentId, int pagina) {
        try {
            var builder = UriComponentsBuilder.fromPath("/v1/invoices")
                    .queryParam("page", pagina)
                    .queryParam("page_size", PAGE_SIZE);

            if (fechaInicio != null) builder.queryParam("date_start", fechaInicio);
            if (fechaFin != null) builder.queryParam("date_end", fechaFin);
            if (documentId != null) builder.queryParam("document_id", documentId);

            var response = siigoRestTemplate.exchange(
                    builder.build().toUriString(),
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    new ParameterizedTypeReference<PaginatedResponse<FacturaResponse>>() {
                    }
            );

            PaginatedResponse<FacturaResponse> body = response.getBody();
            return body == null ? List.of() : body.resultsOrEmpty();

        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            tokenStore.invalidate();
            throw new UnauthorizedException("No autorizado listando facturas en Siigo", ex);
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("listarFacturas", ex);
        }
    }
}
