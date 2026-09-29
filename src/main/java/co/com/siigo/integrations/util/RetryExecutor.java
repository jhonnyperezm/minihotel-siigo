package co.com.siigo.integrations.util;

import co.com.siigo.integrations.config.IntegrationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

@Component
@RequiredArgsConstructor
public class RetryExecutor {

  private final IntegrationProperties props;

  @FunctionalInterface
  public interface ThrowingSupplier<T> { T get() throws Exception; }

  public <T> T execute(String opName, ThrowingSupplier<T> supplier) {
    int max = props.getRetry().getMaxAttempts();
    long backoff = props.getRetry().getBackoffMs();

    Exception last = null;

    for (int attempt = 1; attempt <= max; attempt++) {
      try {
        return supplier.get();
      } catch (ResourceAccessException ex) {
        last = ex;
        sleep(backoff, attempt);
      } catch (Exception ex) {
        throw new RuntimeException("Falló operación: " + opName + " -> " + ex.getMessage(), ex);
      }
    }

    throw new RuntimeException("Falló operación: " + opName + " -> " + (last != null ? last.getMessage() : "null"), last);
  }

  private void sleep(long baseBackoff, int attempt) {
    try { Thread.sleep(baseBackoff * attempt); }
    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
  }
}
