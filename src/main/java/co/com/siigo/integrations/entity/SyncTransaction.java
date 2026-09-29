package co.com.siigo.integrations.entity;

import co.com.siigo.integrations.util.Util;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Registro de auditoría de cada intento de sincronización de una reservación hacia Siigo.
 *
 * <p>Cambios frente a la versión de World Office:
 * <ul>
 *   <li>{@code siigoId} es un {@code String}: Siigo identifica sus documentos con un GUID,
 *       no con un consecutivo numérico.</li>
 *   <li>{@code siigoTenant} sustituye a {@code siigoTenant}. Como cada empresa de Siigo es
 *       un tenant independiente, aquí se guarda la clave que permite recuperar el hotel
 *       correcto al reintentar.</li>
 *   <li>Se agregan {@code estadoDian} y {@code cufe}, que solo existen en facturación
 *       electrónica y son la evidencia de que la DIAN aceptó el documento.</li>
 * </ul>
 */
@Entity
@Table(name = "sync_transactions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_reservation_hotel",
                columnNames = {"reservation_number", "minihotel_hotel_id"}
        ))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_number", nullable = false)
    private String reservationNumber;

    @Column(name = "minihotel_hotel_id")
    private String minihotelHotelId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private SyncStatus status;

    /** GUID de la factura en Siigo (p. ej. {@code 63f918c2-ca65-4edc-a7db-66bcdd5159fb}). */
    @Column(name = "siigo_id")
    private String siigoId;

    /** Nombre visible del documento en Siigo (p. ej. {@code FV-2-22}). */
    @Column(name = "siigo_numero")
    private String siigoNumero;

    @Column(name = "siigo_fecha")
    private LocalDateTime siigoFecha;

    /** Clave del tenant de Siigo con el que se emitió, para poder reintentar. */
    @Column(name = "siigo_tenant")
    private String siigoTenant;

    /** Estado del documento electrónico: {@code Draft}, {@code Accepted} o {@code Rejected}. */
    @Column(name = "estado_dian")
    private String estadoDian;

    /** Código único de facturación electrónica devuelto por la DIAN. */
    @Column(name = "cufe", length = 200)
    private String cufe;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "request_payload", columnDefinition = "TEXT")
    private String requestPayload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(Util.ZONA_BOGOTA);
    }

    public enum SyncStatus {
        SUCCESS,
        FAILED,
        PENDING
    }
}
