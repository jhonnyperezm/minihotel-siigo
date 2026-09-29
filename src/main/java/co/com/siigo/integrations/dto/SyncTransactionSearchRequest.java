package co.com.siigo.integrations.dto;

import java.time.LocalDateTime;

public record SyncTransactionSearchRequest(
        LocalDateTime startDate,
        LocalDateTime endDate,
        String status
) {
}

