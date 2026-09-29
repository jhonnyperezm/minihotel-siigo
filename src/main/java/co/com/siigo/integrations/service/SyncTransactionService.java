package co.com.siigo.integrations.service;

import co.com.siigo.integrations.dto.SyncTransactionResponse;
import co.com.siigo.integrations.entity.SyncTransaction;
import co.com.siigo.integrations.dto.siigo.FacturaResponse;
import co.com.siigo.integrations.entity.SyncTransaction.SyncStatus;
import co.com.siigo.integrations.repository.SyncTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Servicio para gestionar el ciclo de vida de las transacciones de sincronización.
 * Registra el estado de cada intento de sincronización (PENDING → SUCCESS / FAILED)
 * y provee métodos de consulta para el historial de auditoría.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncTransactionService {

    private final SyncTransactionRepository repository;

    /**
     * Crea una nueva transacción en estado {@code PENDING} para una reservación.
     * <p>
     * Valida que no exista ya una transacción para la misma reservación y hotel antes de insertar.
     * Si ya existe, lanza una excepción con información del registro previo (ID, estado,
     * número de factura de Siigo y fecha de sincronización) para facilitar el diagnóstico.
     *
     * @param reservationNumber número de reservación de MiniHotel
     * @param contexto          contexto de facturación del hotel (tenant de Siigo e ID de hotel en MiniHotel)
     * @param requestPayload    payload JSON enviado a Siigo (puede ser null)
     * @return transacción recién creada
     * @throws IllegalStateException si ya existe una transacción para esta reservación y hotel
     */
    @Transactional
    public SyncTransaction createPendingTransaction(String reservationNumber, IntegrationService.ContextoSiigo contexto, String requestPayload) {
        repository.findByReservationNumberAndMinihotelHotelId(reservationNumber, contexto.minihotelId())
                .ifPresent(existing -> {
                    String facturaInfo = existing.getSiigoNumero() != null
                            ? "Factura Siigo: " + existing.getSiigoNumero() + ". "
                            : "";
                    String fechaInfo = existing.getCreatedAt() != null
                            ? "Fecha de sincronización: " + existing.getCreatedAt().toString().replace("T", " ") + ". "
                            : "";
                    throw new IllegalStateException(
                            "La reservación '" + reservationNumber + "' ya existe para este hotel " +
                            "(ID transacción: " + existing.getId() + ", estado: " + existing.getStatus() + "). " +
                            facturaInfo + fechaInfo +
                            "Use la opción de reintento para volver a sincronizarla.");
                });

        SyncTransaction transaction = SyncTransaction.builder()
                .reservationNumber(reservationNumber)
                .minihotelHotelId(contexto.minihotelId())
                .siigoTenant(contexto.siigoTenant())
                .status(SyncStatus.PENDING)
                .requestPayload(requestPayload)
                .build();
        return repository.save(transaction);
    }

    /**
     * Indica si la reservación ya tiene una transacción registrada para el hotel,
     * sin importar su estado.
     */
    @Transactional(readOnly = true)
    public boolean existeTransaccion(String reservationNumber, String minihotelHotelId) {
        return repository.findByReservationNumberAndMinihotelHotelId(reservationNumber, minihotelHotelId)
                .isPresent();
    }

    /**
     * Resetea una transacción existente al estado {@code PENDING} para reintento.
     * Limpia el mensaje de error, el ID/número/fecha de la factura de Siigo, el estado DIAN y el payload previo,
     * sin eliminar el registro original del historial.
     *
     * @param transactionId ID de la transacción a resetear
     * @return transacción actualizada en estado {@code PENDING}
     * @throws IllegalArgumentException si no se encuentra la transacción con ese ID
     */
    @Transactional
    public SyncTransaction resetToPending(Long transactionId) {
        SyncTransaction transaction = repository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        transaction.setStatus(SyncStatus.PENDING);
        transaction.setErrorMessage(null);
        transaction.setSiigoId(null);
        transaction.setSiigoNumero(null);
        transaction.setSiigoFecha(null);
        transaction.setEstadoDian(null);
        transaction.setCufe(null);
        transaction.setRequestPayload(null);

        log.info("Transacción {} reiniciada a PENDING para reintento", transactionId);
        return repository.save(transaction);
    }

    /**
     * Marca una transacción como exitosa tras la creación de la factura en Siigo.
     * Guarda el GUID, el número visible, la fecha y —si el comprobante es electrónico—
     * el estado ante la DIAN y el CUFE.
     *
     * @param transactionId ID de la transacción
     * @param factura       respuesta devuelta por Siigo al crear la factura
     * @return transacción actualizada en estado {@code SUCCESS}
     * @throws IllegalArgumentException si no se encuentra la transacción con ese ID
     */
    @Transactional
    public SyncTransaction markAsSuccess(Long transactionId, FacturaResponse factura) {
        SyncTransaction transaction = repository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        transaction.setStatus(SyncStatus.SUCCESS);
        transaction.setSiigoId(factura.id());
        transaction.setSiigoNumero(factura.numeroDocumento());
        transaction.setSiigoFecha(parsearFecha(factura.date()));

        if (factura.stamp() != null) {
            transaction.setEstadoDian(factura.stamp().status());
            transaction.setCufe(factura.stamp().cufe());
        }

        log.info("Transacción {} marcada como SUCCESS. Factura Siigo: {} (id {})",
                transactionId, transaction.getSiigoNumero(), transaction.getSiigoId());
        return repository.save(transaction);
    }

    /**
     * Actualiza el estado del documento electrónico tras un envío o reenvío a la DIAN.
     */
    @Transactional
    public void actualizarEstadoDian(Long transactionId, String estado, String cufe) {
        repository.findById(transactionId).ifPresent(transaction -> {
            transaction.setEstadoDian(estado);
            if (cufe != null && !cufe.isBlank()) {
                transaction.setCufe(cufe);
            }
            repository.save(transaction);
            log.info("Transacción {}: estado DIAN actualizado a {}", transactionId, estado);
        });
    }

    /**
     * Siigo devuelve la fecha del documento como {@code yyyy-MM-dd}; se guarda a medianoche.
     */
    private LocalDateTime parsearFecha(String fecha) {
        if (fecha == null || fecha.isBlank()) return null;
        try {
            return LocalDate.parse(fecha.substring(0, 10)).atStartOfDay();
        } catch (Exception ex) {
            log.warn("No se pudo interpretar la fecha '{}' devuelta por Siigo", fecha);
            return null;
        }
    }

    /**
     * Marca una transacción como fallida y persiste el error y el payload que se intentó enviar.
     *
     * @param transactionId  ID de la transacción
     * @param errorMessage   mensaje de error capturado
     * @param requestPayload payload JSON que se intentó enviar a Siigo (puede ser null)
     * @return transacción actualizada en estado {@code FAILED}
     * @throws IllegalArgumentException si no se encuentra la transacción con ese ID
     */
    @Transactional
    public SyncTransaction markAsFailed(Long transactionId, String errorMessage, String requestPayload) {

        SyncTransaction transaction = repository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

        transaction.setStatus(SyncStatus.FAILED);
        transaction.setErrorMessage(errorMessage);
        transaction.setRequestPayload(requestPayload);

        log.error("Transacción {} marcada como FAILED. Error: {}", transactionId, errorMessage);
        return repository.save(transaction);
    }

    /**
     * Busca transacciones creadas dentro de un rango de fechas, ordenadas por fecha descendente.
     *
     * @param startDate inicio del rango (inclusive)
     * @param endDate   fin del rango (inclusive)
     * @return lista de transacciones en el rango indicado
     */
    @Transactional(readOnly = true)
    public List<SyncTransactionResponse> findByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return repository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Busca transacciones en un rango de fechas filtradas por estado.
     *
     * @param startDate inicio del rango (inclusive)
     * @param endDate   fin del rango (inclusive)
     * @param status    estado a filtrar: {@code "PENDING"}, {@code "SUCCESS"} o {@code "FAILED"}
     * @return lista de transacciones que cumplen ambos criterios
     */
    @Transactional(readOnly = true)
    public List<SyncTransactionResponse> findByDateRangeAndStatus(LocalDateTime startDate, LocalDateTime endDate, String status) {
        SyncStatus syncStatus = status != null ? SyncStatus.valueOf(status.toUpperCase()) : null;
        return repository.findByDateRangeAndStatus(startDate, endDate, syncStatus)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Busca la transacción asociada a un número de reservación.
     *
     * @param reservationNumber número de reservación de MiniHotel
     * @return Optional con la transacción si existe
     */
    @Transactional(readOnly = true)
    public Optional<SyncTransactionResponse> findByReservationNumber(String reservationNumber) {
        return repository.findByReservationNumber(reservationNumber)
                .map(this::toResponse);
    }

    /**
     * Devuelve todas las transacciones en estado {@code FAILED} para reintento.
     *
     * @return lista de entidades con estado FAILED
     */
    @Transactional(readOnly = true)
    public List<SyncTransaction> findFailedTransactions() {
        return repository.findByStatus(SyncStatus.FAILED);
    }

    /**
     * Obtiene todas las transacciones de sincronización ordenadas por fecha descendente.
     *
     * @return lista completa de transacciones
     */
    @Transactional(readOnly = true)
    public List<SyncTransactionResponse> findAll() {
        return repository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Obtiene una transacción por su ID.
     *
     * @param id identificador de la transacción
     * @return Optional con la transacción si existe
     */
    @Transactional(readOnly = true)
    public Optional<SyncTransactionResponse> findById(Long id) {
        return repository.findById(id)
                .map(this::toResponse);
    }

    /**
     * Convierte una entidad {@code SyncTransaction} al DTO de respuesta.
     *
     * @param entity entidad de base de datos
     * @return DTO listo para serializar en la respuesta REST
     */
    private SyncTransactionResponse toResponse(SyncTransaction entity) {
        return new SyncTransactionResponse(
                entity.getId(),
                entity.getReservationNumber(),
                entity.getMinihotelHotelId(),
                entity.getStatus().name(),
                entity.getSiigoId(),
                entity.getSiigoNumero(),
                entity.getSiigoFecha(),
                entity.getSiigoTenant(),
                entity.getEstadoDian(),
                entity.getCufe(),
                entity.getErrorMessage(),
                entity.getCreatedAt()
        );
    }
}
