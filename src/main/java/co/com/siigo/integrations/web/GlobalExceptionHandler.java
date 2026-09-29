package co.com.siigo.integrations.web;

import co.com.siigo.integrations.client.MiniHotelException;
import co.com.siigo.integrations.security.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Traduce las excepciones a respuestas JSON.
 *
 * <p>Regla: esta aplicación solo responde 401 cuando falla su propio Basic Auth (Spring
 * Security, antes de llegar aquí). Los errores de MiniHotel o Siigo, incluidos sus 401,
 * salen como 502: el panel cierra la sesión ante cualquier 401 y el usuario no tiene la culpa
 * de que un servicio externo rechace las credenciales de la integración.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(MiniHotelException.class)
  public ResponseEntity<Map<String, Object>> handleMiniHotel(MiniHotelException ex) {
    return badGateway("MINIHOTEL_ERROR", ex.getMessage());
  }

  /** Siigo rechazó las credenciales de la empresa (no las del usuario del panel). */
  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
    return badGateway("SIIGO_UNAUTHORIZED", ex.getMessage());
  }

  @ExceptionHandler(HttpStatusCodeException.class)
  public ResponseEntity<Map<String, Object>> handleHttpStatusCode(HttpStatusCodeException ex) {
    return badGateway("UPSTREAM_ERROR",
        "El servicio externo respondió HTTP " + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString());
  }

  /** Sin conexión, DNS o timeout contra MiniHotel o Siigo. */
  @ExceptionHandler(ResourceAccessException.class)
  public ResponseEntity<Map<String, Object>> handleResourceAccess(ResourceAccessException ex) {
    return badGateway("UPSTREAM_UNREACHABLE", "No se logró comunicación con el servicio externo: " + ex.getMessage());
  }

  private static ResponseEntity<Map<String, Object>> badGateway(String error, String message) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
        "status", 502,
        "error", error,
        "message", message
    ));
  }

  /** Ruta o archivo estático inexistente: 404, no 500. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
        "status", 404,
        "error", "NOT_FOUND",
        "message", "No existe el recurso /" + ex.getResourcePath()
    ));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
        "status", 500,
        "error", "INTERNAL_SERVER_ERROR",
        "message", ex.getMessage()
    ));
  }
}
