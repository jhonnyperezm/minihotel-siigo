package co.com.siigo.integrations.dto;

import java.time.LocalDateTime;

/**
 * Vista de una transacción de sincronización para el panel de administración y la API REST.
 */
public record SyncTransactionResponse(
        Long id,
        String reservationNumber,
        String minihotelHotelId,
        String status,
        String siigoId,
        String siigoNumero,
        LocalDateTime siigoFecha,
        String siigoTenant,
        String estadoDian,
        String cufe,
        String errorMessage,
        LocalDateTime createdAt
) {
}
