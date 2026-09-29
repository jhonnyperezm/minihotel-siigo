package co.com.siigo.integrations.dto.webhook;

import java.time.LocalDateTime;

/**
 * Vista de un evento recibido, para el panel de administración y la API REST.
 */
public record WebhookEventResponse(
        Long id,
        String eventId,
        Long notificationId,
        String hotelCode,
        String hotelName,
        String notificationType,
        String reservationNumber,
        String estado,
        String motivo,
        String errorMessage,
        Integer intentos,
        Long syncTransactionId,
        String payload,
        LocalDateTime recibidoEn,
        LocalDateTime procesadoEn
) {
}
