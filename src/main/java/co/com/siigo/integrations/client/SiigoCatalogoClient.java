package co.com.siigo.integrations.client;

import co.com.siigo.integrations.dto.siigo.BodegaResponse;
import co.com.siigo.integrations.dto.siigo.CentroCostoResponse;
import co.com.siigo.integrations.dto.siigo.FormaPagoResponse;
import co.com.siigo.integrations.dto.siigo.ImpuestoResponse;
import co.com.siigo.integrations.dto.siigo.PaginatedResponse;
import co.com.siigo.integrations.dto.siigo.TipoComprobanteResponse;
import co.com.siigo.integrations.dto.siigo.UsuarioResponse;
import co.com.siigo.integrations.dto.siigo.enums.DocumentoTipoEnum;
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

import java.util.List;

/**
 * Cliente REST de los catálogos de Siigo necesarios para facturar:
 * tipos de comprobante, medios de pago, impuestos, usuarios (vendedores),
 * centros de costo y bodegas.
 *
 * <p>Sustituye a {@code WorldOfficeGlobalClient}. A diferencia de World Office, aquí
 * los catálogos se consultan con GET y query params, sin cuerpos de filtro.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiigoCatalogoClient {

    private final RestTemplate siigoRestTemplate;
    private final SiigoTokenStore tokenStore;

    /**
     * Tipos de comprobante configurados para un tipo de documento.
     *
     * @param tipo FV para facturas de venta, NC para notas crédito, RC para recibos de caja
     */
    public List<TipoComprobanteResponse> listarTiposComprobante(DocumentoTipoEnum tipo) {
        var uri = UriComponentsBuilder.fromPath("/v1/document-types")
                .queryParam("type", tipo.codigo())
                .build()
                .toUriString();

        return getLista(uri, "listarTiposComprobante",
                new ParameterizedTypeReference<List<TipoComprobanteResponse>>() {
                });
    }

    /**
     * Medios de pago habilitados para un tipo de documento.
     * Siigo exige el query param {@code document_type}.
     */
    public List<FormaPagoResponse> listarFormasPago(DocumentoTipoEnum tipo) {
        var uri = UriComponentsBuilder.fromPath("/v1/payment-types")
                .queryParam("document_type", tipo.codigo())
                .build()
                .toUriString();

        return getLista(uri, "listarFormasPago",
                new ParameterizedTypeReference<List<FormaPagoResponse>>() {
                });
    }

    /**
     * Impuestos configurados en la empresa (IVA, INC, retenciones).
     */
    public List<ImpuestoResponse> listarImpuestos() {
        return getLista("/v1/taxes", "listarImpuestos",
                new ParameterizedTypeReference<List<ImpuestoResponse>>() {
                });
    }

    /**
     * Centros de costo, usados para separar la operación de cada hotel.
     */
    public List<CentroCostoResponse> listarCentrosCosto() {
        return getLista("/v1/cost-centers", "listarCentrosCosto",
                new ParameterizedTypeReference<List<CentroCostoResponse>>() {
                });
    }

    /**
     * Bodegas. Solo aplican a renglones de productos con control de inventarios.
     */
    public List<BodegaResponse> listarBodegas() {
        return getLista("/v1/warehouses", "listarBodegas",
                new ParameterizedTypeReference<List<BodegaResponse>>() {
                });
    }

    /**
     * Usuarios de la empresa. El id del usuario vendedor es el que va en el campo
     * {@code seller} de la factura. Este endpoint sí viene paginado.
     */
    public List<UsuarioResponse> listarUsuarios() {
        try {
            var uri = UriComponentsBuilder.fromPath("/v1/users")
                    .queryParam("page", 1)
                    .queryParam("page_size", 100)
                    .build()
                    .toUriString();

            var response = siigoRestTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    new ParameterizedTypeReference<PaginatedResponse<UsuarioResponse>>() {
                    }
            );

            PaginatedResponse<UsuarioResponse> body = response.getBody();
            return body == null ? List.of() : body.resultsOrEmpty();

        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            tokenStore.invalidate();
            throw new UnauthorizedException("No autorizado listando usuarios en Siigo", ex);
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir("listarUsuarios", ex);
        }
    }

    private <T> List<T> getLista(String uri, String operacion, ParameterizedTypeReference<List<T>> tipo) {
        try {
            var response = siigoRestTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(tokenStore.headers()),
                    tipo
            );
            List<T> body = response.getBody();
            return body == null ? List.of() : body;

        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            tokenStore.invalidate();
            throw new UnauthorizedException("No autorizado en la operación " + operacion, ex);
        } catch (HttpStatusCodeException ex) {
            throw SiigoErrores.traducir(operacion, ex);
        }
    }
}
