package co.com.siigo.integrations.client;

/**
 * Error al comunicarse con MiniHotel: respuesta HTTP de error o credenciales rechazadas.
 *
 * <p>Se separa de las excepciones de Spring para que un 401 de MiniHotel no se confunda con
 * un 401 de esta aplicación: el panel interpreta este último como sesión inválida y cierra
 * la sesión del usuario.
 */
public class MiniHotelException extends RuntimeException {

    private final int status;

    public MiniHotelException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** Código HTTP que devolvió MiniHotel. */
    public int getStatus() {
        return status;
    }
}
