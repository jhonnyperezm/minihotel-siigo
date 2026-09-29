package co.com.siigo.integrations.service;

import co.com.siigo.integrations.dto.siigo.FacturaResponse;
import co.com.siigo.integrations.entity.SyncTransaction;
import co.com.siigo.integrations.entity.SyncTransaction.SyncStatus;
import co.com.siigo.integrations.repository.SyncTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncTransactionServiceTest {

    @Mock
    private SyncTransactionRepository repository;

    @InjectMocks
    private SyncTransactionService service;

    private IntegrationService.ContextoSiigo contexto;

    @BeforeEach
    void setUp() {
        contexto = new IntegrationService.ContextoSiigo(1L, 2L, "SUC-A", "H001");
    }

    // ─── createPendingTransaction ─────────────────────────────────────────

    @Test
    void createPendingTransaction_noExisting_savesAndReturns() {
        when(repository.findByReservationNumberAndMinihotelHotelId("RES-001", "H001"))
                .thenReturn(Optional.empty());
        SyncTransaction saved = buildTransaction(1L, "RES-001", SyncStatus.PENDING);
        when(repository.save(any())).thenReturn(saved);

        SyncTransaction result = service.createPendingTransaction("RES-001", contexto, null);

        assertThat(result.getId()).isEqualTo(1L);
        ArgumentCaptor<SyncTransaction> captor = ArgumentCaptor.forClass(SyncTransaction.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(captor.getValue().getReservationNumber()).isEqualTo("RES-001");
        assertThat(captor.getValue().getMinihotelHotelId()).isEqualTo("H001");
        assertThat(captor.getValue().getSiigoTenant()).isEqualTo("SUC-A");
    }

    @Test
    void createPendingTransaction_duplicateExists_throwsIllegalState() {
        SyncTransaction existing = buildTransaction(5L, "RES-001", SyncStatus.SUCCESS);
        existing.setSiigoNumero("FAC-100");
        when(repository.findByReservationNumberAndMinihotelHotelId("RES-001", "H001"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createPendingTransaction("RES-001", contexto, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RES-001")
                .hasMessageContaining("SUCCESS")
                .hasMessageContaining("5");
    }

    @Test
    void createPendingTransaction_duplicate_messageIncludesSiigoNumeroAndFecha() {
        SyncTransaction existing = buildTransaction(5L, "RES-001", SyncStatus.SUCCESS);
        existing.setSiigoNumero("FAC-100");
        existing.setCreatedAt(LocalDateTime.of(2026, 3, 1, 10, 0));
        when(repository.findByReservationNumberAndMinihotelHotelId("RES-001", "H001"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createPendingTransaction("RES-001", contexto, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAC-100")
                .hasMessageContaining("2026-03-01");
    }

    // ─── resetToPending ───────────────────────────────────────────────────

    @Test
    void resetToPending_existingTransaction_clearsFieldsAndSaves() {
        SyncTransaction tx = buildTransaction(3L, "RES-002", SyncStatus.FAILED);
        tx.setErrorMessage("algo falló");
        tx.setSiigoNumero("WO-9");
        when(repository.findById(3L)).thenReturn(Optional.of(tx));
        when(repository.save(tx)).thenReturn(tx);

        SyncTransaction result = service.resetToPending(3L);

        assertThat(result.getStatus()).isEqualTo(SyncStatus.PENDING);
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getSiigoNumero()).isNull();
        assertThat(result.getSiigoId()).isNull();
        assertThat(result.getSiigoFecha()).isNull();
        assertThat(result.getRequestPayload()).isNull();
    }

    @Test
    void resetToPending_notFound_throwsIllegalArgument() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetToPending(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // ─── markAsSuccess ────────────────────────────────────────────────────

    @Test
    void markAsSuccess_guardaGuidNumeroYFechaDeSiigo() {
        SyncTransaction tx = buildTransaction(1L, "RES-001", SyncStatus.PENDING);
        when(repository.findById(1L)).thenReturn(Optional.of(tx));
        when(repository.save(tx)).thenReturn(tx);

        SyncTransaction result = service.markAsSuccess(1L, factura("FV-2-22", null, null));

        assertThat(result.getStatus()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(result.getSiigoId()).isEqualTo("guid-123");
        assertThat(result.getSiigoNumero()).isEqualTo("FV-2-22");
        assertThat(result.getSiigoFecha()).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
    }

    @Test
    void markAsSuccess_conTimbrado_guardaEstadoDianYCufe() {
        SyncTransaction tx = buildTransaction(1L, "RES-001", SyncStatus.PENDING);
        when(repository.findById(1L)).thenReturn(Optional.of(tx));
        when(repository.save(tx)).thenReturn(tx);

        SyncTransaction result = service.markAsSuccess(1L, factura("FV-2-22", "Accepted", "CUFE-XYZ"));

        assertThat(result.getEstadoDian()).isEqualTo("Accepted");
        assertThat(result.getCufe()).isEqualTo("CUFE-XYZ");
    }

    @Test
    void markAsSuccess_sinNombre_componeElNumeroConPrefijo() {
        SyncTransaction tx = buildTransaction(1L, "RES-001", SyncStatus.PENDING);
        when(repository.findById(1L)).thenReturn(Optional.of(tx));
        when(repository.save(tx)).thenReturn(tx);

        SyncTransaction result = service.markAsSuccess(1L, factura(null, null, null));

        assertThat(result.getSiigoNumero()).isEqualTo("FV-25");
    }

    @Test
    void markAsSuccess_notFound_throwsIllegalArgument() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsSuccess(1L, factura("FV-2-22", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── actualizarEstadoDian ─────────────────────────────────────────────

    @Test
    void actualizarEstadoDian_persisteElNuevoEstado() {
        SyncTransaction tx = buildTransaction(1L, "RES-001", SyncStatus.SUCCESS);
        when(repository.findById(1L)).thenReturn(Optional.of(tx));
        when(repository.save(tx)).thenReturn(tx);

        service.actualizarEstadoDian(1L, "Accepted", "CUFE-999");

        assertThat(tx.getEstadoDian()).isEqualTo("Accepted");
        assertThat(tx.getCufe()).isEqualTo("CUFE-999");
    }

    @Test
    void actualizarEstadoDian_transaccionInexistente_noHaceNada() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        service.actualizarEstadoDian(99L, "Rejected", null);

        verify(repository, never()).save(any());
    }

    /**
     * Factura de prueba. Cuando {@code name} es null se ejercita el armado
     * del número a partir de prefijo y consecutivo.
     */
    private FacturaResponse factura(String name, String estadoDian, String cufe) {
        FacturaResponse.Sello sello = estadoDian == null
                ? null
                : new FacturaResponse.Sello(estadoDian, cufe, null, null, null);

        return new FacturaResponse("guid-123", name, "FV", 25, "2026-03-01",
                null, null, null, sello, null, false, null);
    }

    // ─── markAsFailed ─────────────────────────────────────────────────────

    @Test
    void markAsFailed_updatesStatusAndError() {
        SyncTransaction tx = buildTransaction(2L, "RES-002", SyncStatus.PENDING);
        when(repository.findById(2L)).thenReturn(Optional.of(tx));
        when(repository.save(tx)).thenReturn(tx);

        SyncTransaction result = service.markAsFailed(2L, "Error de conexión", "{\"req\":1}");

        assertThat(result.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("Error de conexión");
        assertThat(result.getRequestPayload()).isEqualTo("{\"req\":1}");
    }

    @Test
    void markAsFailed_notFound_throwsIllegalArgument() {
        when(repository.findById(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAsFailed(2L, "err", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── findById ─────────────────────────────────────────────────────────

    @Test
    void findById_existing_returnsResponse() {
        SyncTransaction tx = buildTransaction(1L, "RES-001", SyncStatus.SUCCESS);
        tx.setSiigoNumero("FAC-1");
        tx.setCreatedAt(LocalDateTime.now());
        when(repository.findById(1L)).thenReturn(Optional.of(tx));

        var result = service.findById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(1L);
        assertThat(result.get().reservationNumber()).isEqualTo("RES-001");
    }

    @Test
    void findById_notFound_returnsEmpty() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.findById(99L)).isEmpty();
    }

    // ─── findAll ──────────────────────────────────────────────────────────

    @Test
    void findAll_returnsMappedResponses() {
        SyncTransaction tx1 = buildTransaction(1L, "RES-001", SyncStatus.SUCCESS);
        SyncTransaction tx2 = buildTransaction(2L, "RES-002", SyncStatus.FAILED);
        tx1.setCreatedAt(LocalDateTime.now());
        tx2.setCreatedAt(LocalDateTime.now());
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(tx1, tx2));

        var result = service.findAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).status()).isEqualTo("SUCCESS");
        assertThat(result.get(1).status()).isEqualTo("FAILED");
    }

    // ─── findByDateRange ──────────────────────────────────────────────────

    @Test
    void findByDateRange_returnsFilteredList() {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 31, 23, 59);
        SyncTransaction tx = buildTransaction(1L, "RES-001", SyncStatus.SUCCESS);
        tx.setCreatedAt(LocalDateTime.of(2026, 1, 15, 10, 0));
        when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to)).thenReturn(List.of(tx));

        var result = service.findByDateRange(from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).reservationNumber()).isEqualTo("RES-001");
    }

    // ─── findByDateRangeAndStatus ─────────────────────────────────────────

    @Test
    void findByDateRangeAndStatus_filtersCorrectly() {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 1, 31, 23, 59);
        SyncTransaction tx = buildTransaction(1L, "RES-001", SyncStatus.FAILED);
        tx.setCreatedAt(from);
        when(repository.findByDateRangeAndStatus(from, to, SyncStatus.FAILED)).thenReturn(List.of(tx));

        var result = service.findByDateRangeAndStatus(from, to, "FAILED");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("FAILED");
    }

    // ─── findByReservationNumber ──────────────────────────────────────────

    @Test
    void findByReservationNumber_existing_returnsResponse() {
        SyncTransaction tx = buildTransaction(1L, "RES-001", SyncStatus.SUCCESS);
        tx.setCreatedAt(LocalDateTime.now());
        when(repository.findByReservationNumber("RES-001")).thenReturn(Optional.of(tx));

        var result = service.findByReservationNumber("RES-001");

        assertThat(result).isPresent();
        assertThat(result.get().reservationNumber()).isEqualTo("RES-001");
    }

    @Test
    void findByReservationNumber_notFound_returnsEmpty() {
        when(repository.findByReservationNumber("X")).thenReturn(Optional.empty());

        assertThat(service.findByReservationNumber("X")).isEmpty();
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private SyncTransaction buildTransaction(Long id, String reservationNumber, SyncStatus status) {
        return SyncTransaction.builder()
                .id(id)
                .reservationNumber(reservationNumber)
                .minihotelHotelId("H001")
                .siigoTenant("SUC-A")
                .status(status)
                .build();
    }
}
