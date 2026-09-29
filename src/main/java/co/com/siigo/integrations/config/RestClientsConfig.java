package co.com.siigo.integrations.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import co.com.siigo.integrations.client.MiniHotelException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Configuration
public class RestClientsConfig {

  @Bean
  public RestTemplate miniHotelRestTemplate(RestTemplateBuilder builder, IntegrationProperties props) {
    return builder
        .rootUri(props.getMinihotel().getBaseUrl())
        .setConnectTimeout(Duration.ofMillis(props.getMinihotel().getTimeoutMs()))
        .setReadTimeout(Duration.ofMillis(props.getMinihotel().getTimeoutMs()))
        .errorHandler(new MiniHotelErrorHandler())
        .additionalInterceptors(new MiniHotelLoggingInterceptor())
        .build();
  }

  /**
   * Registra cada llamada a MiniHotel: método, URL, estado y duración en INFO; el XML enviado
   * en DEBUG, con la clave enmascarada porque viaja en el cuerpo de cada petición.
   */
  static class MiniHotelLoggingInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
      if (log.isDebugEnabled()) {
        String xml = new String(body, StandardCharsets.UTF_8)
            .replaceAll("password=\"[^\"]*\"", "password=\"****\"");
        log.debug("MiniHotel -> {} {} body: {}", request.getMethod(), request.getURI(), xml);
      }
      long inicio = System.currentTimeMillis();
      ClientHttpResponse response = execution.execute(request, body);
      log.info("MiniHotel <- {} {} HTTP {} ({} ms)", request.getMethod(), request.getURI(),
          response.getStatusCode().value(), System.currentTimeMillis() - inicio);
      return response;
    }
  }

  /**
   * Convierte las respuestas de error de MiniHotel en {@link MiniHotelException}, con un
   * mensaje que dice qué falló en lugar del 401/500 crudo.
   */
  static class MiniHotelErrorHandler extends DefaultResponseErrorHandler {
    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
      try {
        super.handleError(url, method, response);
      } catch (HttpStatusCodeException ex) {
        int status = ex.getStatusCode().value();
        String mensaje = (status == 401 || status == 403)
            ? "MiniHotel rechazó las credenciales del hotel (HTTP " + status
                + "). Revise usuario, clave y hotel-id en minihotel-auth."
            : "MiniHotel respondió con error HTTP " + status + " en " + url.getPath();
        log.error("{} Respuesta: {}", mensaje, ex.getResponseBodyAsString());
        throw new MiniHotelException(mensaje, status, ex);
      }
    }
  }

  /**
   * Cliente HTTP contra Siigo API. Se apunta al host raíz porque la autenticación
   * cuelga de {@code /auth} y el resto de recursos de {@code /v1/...}.
   */
  @Bean
  public RestTemplate siigoRestTemplate(RestTemplateBuilder builder, IntegrationProperties props) {
    return builder
        .rootUri(props.getSiigo().getBaseUrl())
        .setConnectTimeout(Duration.ofMillis(props.getSiigo().getTimeoutMs()))
        .setReadTimeout(Duration.ofMillis(props.getSiigo().getTimeoutMs()))
        .build();
  }

  /**
   * Pool dedicado a las operaciones asíncronas contra Siigo (envío a la DIAN y por correo).
   * Evita crear un hilo nuevo por llamada, que es el comportamiento por defecto de
   * {@code @Async} sin executor.
   */
  @Bean(name = "siigoAsyncExecutor")
  public TaskExecutor siigoAsyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(5);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("siigo-async-");
    executor.initialize();
    return executor;
  }

  /**
   * Pool dedicado al procesamiento de webhooks de MiniHotel.
   *
   * <p>Está separado del pool de Siigo a propósito: el controlador de webhooks debe responder
   * en menos de 15 segundos, y si compartiera pool con los envíos a la DIAN una ráfaga de
   * timbrados podría dejar los eventos esperando en cola.
   *
   * <p>La política de rechazo es CallerRuns: si la cola se llena, el evento se procesa en el
   * hilo que lo encoló en vez de descartarse. Prefiere ser lento antes que perder una factura.
   */
  @Bean(name = "webhookExecutor")
  public TaskExecutor webhookExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(3);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("webhook-");
    executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    executor.initialize();
    return executor;
  }
}
