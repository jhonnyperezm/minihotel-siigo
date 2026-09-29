package co.com.siigo.integrations.service;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.entity.WebhookEvent;
import co.com.siigo.integrations.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Procesamiento asíncrono de los eventos de webhook.
 *
 * <p><b>Por qué es asíncrono:</b> MiniHotel da 15 segundos para responder y marca como fallida
 * cualquier entrega que exceda ese plazo. Facturar implica consultar el balance en MiniHotel,
 * autenticar en Siigo, resolver el tercero, emitir la factura y —si está activo— timbrarla ante
 * la DIAN. El timbrado por sí solo puede superar ese tiempo. El controlador responde en cuanto
 * guarda el evento, y el trabajo real ocurre aquí.
 *
 * <p>El webhook es únicamente el disparador: los cargos siguen consultándose con
 * {@code GetReservationBalance()}, porque el payload de MiniHotel no los incluye.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookProcessingService {

    private final WebhookEventRepository repository;
    private final WebhookEventService webhookEventService;
    private final IntegrationService integrationService;
    private final SyncTransactionService syncTransactionService;
    private final HotelContextService hotelContextService;
    private final IntegrationProperties props;

    /**
     * Encola el procesamiento del evento en el pool de webhooks.
     * El controlador ya respondió a MiniHotel cuando este método empieza a ejecutarse.
     */
    @Async("webhookExecutor")
    public void procesarAsync(Long eventoId) {
        procesar(eventoId);
    }

    /**
     * Procesa un evento: resuelve el hotel, factura en Siigo y actualiza la auditoría.
     *
     * <p>No lanza excepciones hacia arriba: cualquier fallo queda registrado en el evento,
     * que puede reintentarse desde el panel. Un error aquí nunca debe tumbar el hilo del pool.
     */
    public void procesar(Long eventoId) {
        Optional<WebhookEvent> encontrado = repository.findById(eventoId);
        if (encontrado.isEmpty()) {
            log.warn("[WEBHOOK] Se pidió procesar el evento {}, pero ya no existe", eventoId);
            return;
        }

        WebhookEvent evento = encontrado.get();
        String reservationNumber = evento.getReservationNumber();

        webhookEventService.marcarProcesando(eventoId);

        Optional<IntegrationProperties.HotelConfig> hotel =
                hotelContextService.findHotelByMinihotelId(evento.getHotelCode());

        if (hotel.isEmpty()) {
            webhookEventService.marcarFallido(eventoId,
                    "No hay ningún hotel configurado con hotel-id '" + evento.getHotelCode()
                    + "'. Agrega el hotel en integrations.hotels o corrige su minihotel-auth.hotel-id.");
            return;
        }

        try {
            hotelContextService.setCurrentHotel(hotel.get().getHotelKey());

            log.info("[WEBHOOK] Facturando la reserva {} del hotel '{}' por evento {}",
                    reservationNumber, hotel.get().getHotelName(), eventoId);

            var facturas = integrationService.syncFacturasASiigo(reservationNumber);

            if (facturas.isEmpty()) {
                // El flujo terminó sin factura: reserva excluida por origen, o error ya
                // registrado en sync_transactions. Se busca la transacción para dar contexto.
                webhookEventService.marcarFallido(eventoId, describirFalloSinFactura(reservationNumber));
                return;
            }

            var factura = facturas.get(0);
            Long syncTransactionId = buscarTransaccion(reservationNumber);

            webhookEventService.marcarProcesado(eventoId, syncTransactionId,
                    "Factura Siigo " + factura.numeroDocumento());

        } catch (Exception ex) {
            log.error("[WEBHOOK] Error procesando el evento {} (reserva {}): {}",
                    eventoId, reservationNumber, ex.getMessage(), ex);
            webhookEventService.marcarFallido(eventoId, ex.getMessage());
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Explica por qué el proceso terminó sin factura, apoyándose en la transacción de
     * sincronización cuando existe.
     */
    private String describirFalloSinFactura(String reservationNumber) {
        return syncTransactionService.findByReservationNumber(reservationNumber)
                .map(tx -> tx.errorMessage() != null && !tx.errorMessage().isBlank()
                        ? "No se generó factura. " + tx.errorMessage()
                        : "No se generó factura. Estado de la sincronización: " + tx.status())
                .orElse("No se generó factura. La reservación pudo quedar excluida por su origen "
                        + "(Airbnb, Expedia) o por la marca de pago del huésped.");
    }

    private Long buscarTransaccion(String reservationNumber) {
        return syncTransactionService.findByReservationNumber(reservationNumber)
                .map(tx -> tx.id())
                .orElse(null);
    }

    /** Días de retención configurados, expuesto para los endpoints de mantenimiento. */
    public int getRetenerDias() {
        return props.getWebhooks().getRetenerDias();
    }
}
