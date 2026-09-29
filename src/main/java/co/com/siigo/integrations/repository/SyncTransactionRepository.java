package co.com.siigo.integrations.repository;

import co.com.siigo.integrations.entity.SyncTransaction;
import co.com.siigo.integrations.entity.SyncTransaction.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SyncTransactionRepository extends JpaRepository<SyncTransaction, Long> {

    Optional<SyncTransaction> findByReservationNumber(String reservationNumber);

    Optional<SyncTransaction> findByReservationNumberAndMinihotelHotelId(String reservationNumber, String minihotelHotelId);

    List<SyncTransaction> findByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate);

    List<SyncTransaction> findByStatus(SyncStatus status);

    @Query("SELECT s FROM SyncTransaction s WHERE s.createdAt BETWEEN :startDate AND :endDate AND (:status IS NULL OR s.status = :status)")
    List<SyncTransaction> findByDateRangeAndStatus(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("status") SyncStatus status
    );

    List<SyncTransaction> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime startDate, LocalDateTime endDate);

    List<SyncTransaction> findAllByOrderByCreatedAtDesc();
}
