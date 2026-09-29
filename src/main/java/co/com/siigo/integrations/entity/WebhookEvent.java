package co.com.siigo.integrations.entity;

import co.com.siigo.integrations.util.Util;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Evento recibido desde los webhooks de MiniHotel.
 *
 * <p>Guardar el evento antes de procesarlo es lo que permite responder {@code 200 OK} en
 * milisegundos: MiniHotel considera fallida cualquier entrega que no reciba respuesta en
 * 15 segundos, y facturar en Siigo (más el timbrado ante la DIAN) puede tardar más que eso.
 *
 * <p>La restricción única sobre {@code (hotel_code, notification_id)} implementa la
 * idempotencia frente a los reintentos de MiniHotel, que son seis: a los 10 s, 1 min,
 * 5 min, 10 min, 1 h y 6 h.
 */
@Entity
@Table(name = "webhook_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_webhook_notificacion",
                columnNames = {"hotel_code", "notification_id"}
        ),
        indexes = {
                @Index(name = "idx_webhook_reserva", columnList = "reservation_number"),
                @Index(name = "idx_webhook_estado", columnList = "estado")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** GUID del evento según MiniHotel. No se usa para deduplicar: ver {@code notificationId}. */
    @Column(name = "event_id", length = 100)
    private String eventId;

    /** Identificador incremental de la notificación. Junto con el hotel forma la llave de idempotencia. */
    @Column(name = "notification_id")
    private Long notificationId;

    /** Código de propiedad de MiniHotel. Es lo que permite resolver el tenant de Siigo. */
    @Column(name = "hotel_code", length = 100)
    private String hotelCode;

    @Column(name = "notification_type", length = 100)
    private String notificationType;

    @Column(name = "reservation_number", length = 100)
    private String reservationNumber;

    @Column(name = "estado", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private EstadoWebhook estado;

    /** Motivo por el que el evento se ignoró o se marcó como está. Se muestra en el panel. */
    @Column(name = "motivo", length = 500)
    private String motivo;

    /** JSON completo recibido, para poder diagnosticar sin depender de los logs. */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /** Número de veces que se intentó procesar el evento. */
    @Column(name = "intentos", nullable = false)
    private Integer intentos;

    /** Transacción de sincronización generada, cuando el evento terminó en factura. */
    @Column(name = "sync_transaction_id")
    private Long syncTransactionId;

    @Column(name = "recibido_en", nullable = false)
    private LocalDateTime recibidoEn;

    @Column(name = "procesado_en")
    private LocalDateTime procesadoEn;

    @PrePersist
    protected void onCreate() {
        if (recibidoEn == null) {
            recibidoEn = LocalDateTime.now(Util.ZONA_BOGOTA);
        }
        if (intentos == null) {
            intentos = 0;
        }
        if (estado == null) {
            estado = EstadoWebhook.RECIBIDO;
        }
    }

    /**
     * Estados por los que pasa un evento.
     */
    public enum EstadoWebhook {
        /** Guardado y en cola para procesar. */
        RECIBIDO,
        /** Se está facturando en este momento. */
        PROCESANDO,
        /** Terminó en una factura de Siigo. */
        PROCESADO,
        /** No es un disparador de facturación: se conserva solo como traza. */
        IGNORADO,
        /** Falló el procesamiento. Se puede reintentar desde el panel. */
        FALLIDO
    }
}
