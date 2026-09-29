package co.com.siigo.integrations.service;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.webhook.WebhookEnvelope;
import co.com.siigo.integrations.dto.webhook.WebhookEventResponse;
import co.com.siigo.integrations.entity.WebhookEvent;
import co.com.siigo.integrations.entity.WebhookEvent.EstadoWebhook;
import co.com.siigo.integrations.repository.WebhookEventRepository;
import co.com.siigo.integrations.util.Util;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia y auditoría de los eventos recibidos por webhook.
 *
 * <p>Su responsabilidad principal es la idempotencia: MiniHotel reintenta hasta seis veces
 * una entrega fallida, y un evento ya registrado no debe volver a facturarse. La llave es
 * {@code hotelCode + notificationId}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventService {

    private final WebhookEventRepository repository;
    private final HotelContextService hotelContextService;
    private final IntegrationProperties props;

    /**
     * Registra un evento nuevo.
     *
     * @param envelope sobre ya deserializado
     * @param payload  JSON crudo recibido, para la auditoría
     * @return el evento guardado, o vacío si ya se había registrado (reintento de MiniHotel)
     */
    @Transactional
    public Optional<WebhookEvent> registrar(WebhookEnvelope envelope, String payload) {
        Optional<WebhookEvent> existente = buscarDuplicado(envelope);

        if (existente.isPresent()) {
            log.info("[WEBHOOK] Evento duplicado ignorado ({}). Ya registrado como id {}",
                    envelope.describir(), existente.get().getId());
            return Optional.empty();
        }

        WebhookEvent evento = WebhookEvent.builder()
                .eventId(Util.truncar(envelope.eventId(), 100))
                .notificationId(envelope.notificationId())
                .hotelCode(Util.truncar(envelope.hotelCode(), 100))
                .notificationType(Util.truncar(envelope.notificationType(), 100))
                .reservationNumber(Util.truncar(envelope.reservationNumber(), 100))
                .estado(EstadoWebhook.RECIBIDO)
                .payload(Util.truncar(payload, props.getWebhooks().getMaxPayloadChars()))
                .intentos(0)
                .recibidoEn(LocalDateTime.now(Util.ZONA_BOGOTA))
                .build();

        WebhookEvent guardado = repository.save(evento);
        log.info("[WEBHOOK] Evento registrado id {} · {}", guardado.getId(), envelope.describir());
        return Optional.of(guardado);
    }

    /**
     * Marca el evento como no facturable, dejando constancia del motivo.
     * Se usa para eventos informativos (creación, modificación) y para los tipos desconocidos.
     */
    @Transactional
    public void marcarIgnorado(Long eventoId, String motivo) {
        repository.findById(eventoId).ifPresent(evento -> {
            evento.setEstado(EstadoWebhook.IGNORADO);
            evento.setMotivo(Util.truncar(motivo, 500));
            evento.setProcesadoEn(LocalDateTime.now(Util.ZONA_BOGOTA));
            repository.save(evento);
            log.debug("[WEBHOOK] Evento {} ignorado: {}", eventoId, motivo);
        });
    }

    @Transactional
    public void marcarProcesando(Long eventoId) {
        repository.findById(eventoId).ifPresent(evento -> {
            evento.setEstado(EstadoWebhook.PROCESANDO);
            evento.setIntentos(evento.getIntentos() == null ? 1 : evento.getIntentos() + 1);
            evento.setErrorMessage(null);
            repository.save(evento);
        });
    }

    /**
     * Marca el evento como facturado, enlazándolo con la transacción de sincronización.
     */
    @Transactional
    public void marcarProcesado(Long eventoId, Long syncTransactionId, String motivo) {
        repository.findById(eventoId).ifPresent(evento -> {
            evento.setEstado(EstadoWebhook.PROCESADO);
            evento.setSyncTransactionId(syncTransactionId);
            evento.setMotivo(Util.truncar(motivo, 500));
            evento.setErrorMessage(null);
            evento.setProcesadoEn(LocalDateTime.now(Util.ZONA_BOGOTA));
            repository.save(evento);
            log.info("[WEBHOOK] Evento {} procesado correctamente", eventoId);
        });
    }

    @Transactional
    public void marcarFallido(Long eventoId, String error) {
        repository.findById(eventoId).ifPresent(evento -> {
            evento.setEstado(EstadoWebhook.FALLIDO);
            evento.setErrorMessage(Util.truncar(error, 2000));
            evento.setProcesadoEn(LocalDateTime.now(Util.ZONA_BOGOTA));
            repository.save(evento);
            log.warn("[WEBHOOK] Evento {} marcado como FALLIDO: {}", eventoId, error);
        });
    }

    /**
     * Devuelve un evento a la cola para volver a intentarlo desde el panel.
     *
     * @throws IllegalArgumentException si el evento no existe
     */
    @Transactional
    public WebhookEvent reencolar(Long eventoId) {
        WebhookEvent evento = repository.findById(eventoId)
                .orElseThrow(() -> new IllegalArgumentException("Evento de webhook no encontrado: " + eventoId));

        evento.setEstado(EstadoWebhook.RECIBIDO);
        evento.setErrorMessage(null);
        evento.setProcesadoEn(null);
        return repository.save(evento);
    }

    // ==================== CONSULTAS ====================

    @Transactional(readOnly = true)
    public List<WebhookEventResponse> findAll() {
        return repository.findAllByOrderByRecibidoEnDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<WebhookEventResponse> findByEstado(String estado) {
        EstadoWebhook valor = parseEstado(estado);
        return repository.findByEstadoOrderByRecibidoEnDesc(valor).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<WebhookEventResponse> findByReservationNumber(String reservationNumber) {
        return repository.findByReservationNumberOrderByRecibidoEnDesc(reservationNumber)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<WebhookEventResponse> findByRango(LocalDateTime desde, LocalDateTime hasta) {
        return repository.findByRecibidoEnBetweenOrderByRecibidoEnDesc(desde, hasta)
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Optional<WebhookEventResponse> findById(Long id) {
        return repository.findById(id).map(this::toResponse);
    }

    /**
     * Elimina los eventos anteriores al período de retención configurado.
     *
     * @return cantidad de eventos eliminados
     */
    @Transactional
    public int limpiarAntiguos() {
        LocalDateTime limite = LocalDateTime.now(Util.ZONA_BOGOTA)
                .minusDays(props.getWebhooks().getRetenerDias());

        List<WebhookEvent> antiguos = repository.findByRecibidoEnBefore(limite);
        if (antiguos.isEmpty()) return 0;

        repository.deleteAll(antiguos);
        log.info("[WEBHOOK] Se eliminaron {} eventos anteriores a {}", antiguos.size(), limite.toLocalDate());
        return antiguos.size();
    }

    private Optional<WebhookEvent> buscarDuplicado(WebhookEnvelope envelope) {
        if (envelope.notificationId() == null || envelope.hotelCode() == null) {
            // Sin llave de idempotencia no se puede deduplicar: se registra igual para no perderlo.
            return Optional.empty();
        }
        return repository.findByHotelCodeAndNotificationId(envelope.hotelCode(), envelope.notificationId());
    }

    private EstadoWebhook parseEstado(String estado) {
        try {
            return EstadoWebhook.valueOf(estado.trim().toUpperCase());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Estado de webhook no válido: " + estado
                    + ". Valores admitidos: RECIBIDO, PROCESANDO, PROCESADO, IGNORADO, FALLIDO");
        }
    }

    private WebhookEventResponse toResponse(WebhookEvent evento) {
        String nombreHotel = hotelContextService.findHotelByMinihotelId(evento.getHotelCode())
                .map(IntegrationProperties.HotelConfig::getHotelName)
                .orElse(null);

        return new WebhookEventResponse(
                evento.getId(),
                evento.getEventId(),
                evento.getNotificationId(),
                evento.getHotelCode(),
                nombreHotel,
                evento.getNotificationType(),
                evento.getReservationNumber(),
                evento.getEstado() == null ? null : evento.getEstado().name(),
                evento.getMotivo(),
                evento.getErrorMessage(),
                evento.getIntentos(),
                evento.getSyncTransactionId(),
                evento.getPayload(),
                evento.getRecibidoEn(),
                evento.getProcesadoEn()
        );
    }
}
