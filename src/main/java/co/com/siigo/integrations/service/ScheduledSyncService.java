package co.com.siigo.integrations.service;

import co.com.siigo.integrations.client.SiigoFacturaClient;
import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.siigo.FacturaResponse;
import co.com.siigo.integrations.util.Util;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Sincronización automática de reservaciones hacia Siigo.
 *
 * <p>Tres procesos programados:
 * <ul>
 *   <li>La sincronización cada media hora de los check-out de ayer y de hoy, que crea las
 *       facturas a lo largo del día.</li>
 *   <li>La sincronización nocturna de las salidas del día anterior, que reconcilia y
 *       reintenta las fallidas.</li>
 *   <li>El timbrado de las facturas electrónicas que quedaron en estado {@code Draft},
 *       equivalente al antiguo envío a la DIAN de World Office.</li>
 * </ul>
 *
 * <p>Todos recorren los hoteles activos estableciendo el contexto del tenant, ya que en
 * Siigo cada empresa se autentica por separado.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledSyncService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String SEPARADOR = "═══════════════════════════════════════════════════════════════";
    private static final String SUBSEPARADOR = "───────────────────────────────────────────────────────────────";

    private final IntegrationService integrationService;
    private final HotelContextService hotelContextService;
    private final SiigoCatalogoService catalogoService;
    private final SiigoFacturaClient facturaClient;
    private final SyncTransactionService syncTransactionService;

    /**
     * Sincroniza las reservaciones con salida del día anterior para todos los hoteles activos,
     * y luego reintenta las transacciones que quedaron en FAILED.
     *
     * <p>Con la sincronización cada media hora activa, este proceso casi no encuentra
     * reservas nuevas: su papel es la reconciliación y el reintento de las fallidas.
     */
    @Scheduled(cron = "0 10 0 * * *", zone = "America/Bogota")
    public void syncPreviousDayReservations() {

        LocalDate hoy = Util.hoyEnBogota();
        String dateInicio = hoy.minusDays(1).format(DATE_FORMAT);
        String dateFin = hoy.format(DATE_FORMAT);

        log.info(SEPARADOR);
        log.info("=== Iniciando sincronización automática MiniHotel -> Siigo ===");
        log.info("Rango de fechas de salida: {} a {}", dateInicio, dateFin);
        log.info(SEPARADOR);

        var resumen = sincronizarHotelesActivos(dateInicio, dateFin);
        if (resumen == null) {
            return;
        }

        var reintentos = reintentarFallidas();

        log.info(SEPARADOR);
        log.info("=== Sincronización automática completada ===");
        log.info("  - Hoteles procesados correctamente: {}", resumen.exitosos());
        log.info("  - Hoteles con errores: {}", resumen.fallidos());
        log.info("  - Facturas creadas en Siigo: {}", resumen.facturas());
        if (reintentos.total() > 0) {
            log.info("  - Reintentos exitosos: {}", reintentos.exitosos());
            log.info("  - Reintentos fallidos: {}", reintentos.fallidos());
        }
        log.info(SEPARADOR);
    }

    /**
     * Sincroniza cada media hora (en punto y y media) las reservaciones con check-out de ayer
     * y de hoy.
     *
     * <p>Incluir el día anterior cubre el cambio de fecha: un check-out registrado a las 23:50
     * se recoge en la corrida de las 00:00 aunque su fecha de salida ya sea "ayer". Las reservas
     * que ya tienen transacción se omiten, así que repetir el rango no duplica facturas.
     *
     * <p>No reintenta las fallidas: eso queda en el proceso nocturno, para no insistir contra
     * Siigo cada media hora con un error que suele requerir intervención manual.
     */
    @Scheduled(cron = "0 0/30 * * * *", zone = "America/Bogota")
    public void syncCadaMediaHora() {

        LocalDate hoy = Util.hoyEnBogota();
        String dateInicio = hoy.minusDays(1).format(DATE_FORMAT);
        String dateFin = hoy.format(DATE_FORMAT);

        log.info("=== Sincronización cada media hora MiniHotel -> Siigo · salidas {} a {} ===",
                dateInicio, dateFin);

        var resumen = sincronizarHotelesActivos(dateInicio, dateFin);
        if (resumen == null) {
            return;
        }

        log.info("=== Sincronización cada media hora completada · facturas creadas: {} · hoteles con errores: {} ===",
                resumen.facturas(), resumen.fallidos());
    }

    /**
     * Recorre los hoteles activos sincronizando el rango de salidas indicado.
     *
     * @return resumen de la corrida, o {@code null} si no hay hoteles activos
     */
    private ResumenHoteles sincronizarHotelesActivos(String dateInicio, String dateFin) {

        List<IntegrationProperties.HotelConfig> activeHotels = hotelContextService.getActiveHotels();

        if (activeHotels.isEmpty()) {
            log.warn("No hay hoteles activos configurados para sincronización automática");
            return null;
        }

        log.info("Total de hoteles activos a procesar: {}", activeHotels.size());

        int totalFacturas = 0;
        int hotelesExitosos = 0;
        int hotelesFallidos = 0;

        for (IntegrationProperties.HotelConfig hotel : activeHotels) {
            try {
                log.info(SUBSEPARADOR);
                log.info("Procesando hotel: {} ({})", hotel.getHotelName(), hotel.getHotelKey());
                log.info("  - Usuario MiniHotel: {}", hotel.getMinihotelAuth().getUsername());
                log.info("  - Hotel ID: {}", hotel.getMinihotelAuth().getHotelId());

                hotelContextService.setCurrentHotel(hotel.getHotelKey());
                log.info("  - Tenant Siigo: {}", hotelContextService.getTenantKey());

                var facturas = integrationService.syncFacturasASiigo(dateInicio, dateFin);

                totalFacturas += facturas.size();
                hotelesExitosos++;

                log.info("Hotel '{}' procesado correctamente. Facturas creadas: {}",
                        hotel.getHotelName(), facturas.size());

            } catch (Exception e) {
                hotelesFallidos++;
                log.error("Error al procesar el hotel '{}' ({}): {}",
                        hotel.getHotelName(), hotel.getHotelKey(), e.getMessage(), e);
            } finally {
                hotelContextService.clearCurrentHotel();
            }
        }

        return new ResumenHoteles(hotelesExitosos, hotelesFallidos, totalFacturas);
    }

    /**
     * Envía a la DIAN las facturas electrónicas del día que quedaron sin timbrar.
     *
     * <p>Sustituye al proceso de World Office que primero contabilizaba y luego emitía la
     * factura electrónica: en Siigo la contabilización es automática, así que aquí solo
     * queda el timbrado de las que están en estado {@code Draft}.
     */
    @Scheduled(cron = "0 10 23 * * *", zone = "America/Bogota")
    public void enviarFacturasPendientesADian() {

        String hoy = Util.hoyEnBogota().format(DATE_FORMAT);

        log.info(SEPARADOR);
        log.info("=== Envío de facturas pendientes a la DIAN - Fecha: {} ===", hoy);
        log.info(SEPARADOR);

        for (IntegrationProperties.HotelConfig hotel : hotelContextService.getActiveHotels()) {
            try {
                hotelContextService.setCurrentHotel(hotel.getHotelKey());
                enviarPendientesDelTenant(hotel.getHotelName(), hoy);
            } catch (Exception e) {
                log.error("Error enviando facturas a la DIAN para '{}': {}",
                        hotel.getHotelName(), e.getMessage(), e);
            } finally {
                hotelContextService.clearCurrentHotel();
            }
        }

        log.info(SEPARADOR);
        log.info("=== Envío a la DIAN finalizado ===");
        log.info(SEPARADOR);
    }

    private void enviarPendientesDelTenant(String nombreHotel, String fecha) {
        Long tipoComprobanteId = catalogoService.obtenerTipoComprobanteId();
        var documentos = facturaClient.listarFacturas(fecha, fecha, tipoComprobanteId);

        if (documentos.isEmpty()) {
            log.info("'{}': no hay facturas para la fecha {}", nombreHotel, fecha);
            return;
        }

        int enviadas = 0;
        for (FacturaResponse doc : documentos) {
            if (doc.anulada() || doc.aceptadaPorDian()) {
                continue;
            }

            log.info("'{}': enviando factura {} a la DIAN", nombreHotel, doc.numeroDocumento());
            if (facturaClient.enviarADian(doc.id())) {
                enviadas++;
            } else {
                String errores = facturaClient.consultarErroresDian(doc.id());
                log.warn("'{}': la DIAN no aceptó la factura {}. Detalle: {}",
                        nombreHotel, doc.numeroDocumento(), errores);
            }
        }

        log.info("'{}': {} de {} facturas enviadas a la DIAN", nombreHotel, enviadas, documentos.size());
    }

    /**
     * Reintenta las transacciones en estado FAILED, recuperando el hotel por su tenant.
     */
    private ResumenReintentos reintentarFallidas() {
        var fallidas = syncTransactionService.findFailedTransactions();
        if (fallidas.isEmpty()) {
            return new ResumenReintentos(0, 0);
        }

        log.info(SUBSEPARADOR);
        log.info("=== Reintentando {} transacciones en estado FAILED ===", fallidas.size());

        int exitosos = 0;
        int fallidos = 0;

        for (var tx : fallidas) {
            try {
                log.info("Reintentando reserva '{}' (tx {}, tenant {})",
                        tx.getReservationNumber(), tx.getId(), tx.getSiigoTenant());
                var resultados = retryReservation(tx.getSiigoTenant(), tx.getReservationNumber(), tx.getId());
                exitosos++;
                log.info("Reintento exitoso para la reserva '{}': {} factura(s) creada(s)",
                        tx.getReservationNumber(), resultados.size());
            } catch (Exception e) {
                fallidos++;
                log.error("Reintento fallido para la reserva '{}' (tx {}): {}",
                        tx.getReservationNumber(), tx.getId(), e.getMessage());
            }
        }
        return new ResumenReintentos(exitosos, fallidos);
    }

    /**
     * Sincronización manual de un hotel para una fecha puntual.
     */
    public List<FacturaResponse> syncManual(String hotelKey, String date) {
        return syncManual(hotelKey, date, date);
    }

    /**
     * Sincronización manual de un hotel para un rango de fechas.
     *
     * @throws IllegalArgumentException si el hotel no existe
     */
    public List<FacturaResponse> syncManual(String hotelKey, String fromDate, String toDate) {
        log.info("Sincronización manual del hotel '{}' para el rango {} - {}", hotelKey, fromDate, toDate);

        try {
            if (!hotelContextService.setCurrentHotel(hotelKey)) {
                throw new IllegalArgumentException("No se encontró el hotel con key: " + hotelKey);
            }
            return integrationService.syncFacturasASiigo(fromDate, toDate);
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Sincronización manual de una reservación puntual.
     */
    public List<FacturaResponse> syncManualByReservation(String hotelKey, String reservationNumber) {
        log.info("Sincronizando la reserva '{}' del hotel '{}'", reservationNumber, hotelKey);
        try {
            if (!hotelContextService.setCurrentHotel(hotelKey)) {
                throw new IllegalArgumentException("Hotel no encontrado: " + hotelKey);
            }
            return integrationService.syncFacturasASiigo(reservationNumber);
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Reintenta una reservación reutilizando la transacción existente, ubicando el hotel
     * por el tenant de Siigo guardado en la auditoría.
     */
    public List<FacturaResponse> retryReservation(String siigoTenant, String reservationNumber,
                                                  Long existingTransactionId) {
        log.info("Reintentando la reserva '{}' del tenant '{}'", reservationNumber, siigoTenant);
        try {
            if (!hotelContextService.setCurrentHotelByTenant(siigoTenant)) {
                throw new IllegalArgumentException("Hotel no encontrado para el tenant de Siigo: " + siigoTenant);
            }
            return integrationService.syncFacturasASiigo(reservationNumber, existingTransactionId);
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    /**
     * Sincronización manual de todos los hoteles activos para una fecha.
     */
    public List<FacturaResponse> syncAllHotelsManual(String date) {
        log.info("Sincronización manual de TODOS los hoteles activos - Fecha: {}", date);

        List<FacturaResponse> todas = new ArrayList<>();

        for (IntegrationProperties.HotelConfig hotel : hotelContextService.getActiveHotels()) {
            try {
                log.info("Procesando hotel: {} ({})", hotel.getHotelName(), hotel.getHotelKey());
                hotelContextService.setCurrentHotel(hotel.getHotelKey());

                var facturas = integrationService.syncFacturasASiigo(date, date);
                todas.addAll(facturas);

                log.info("Hotel '{}' procesado: {} facturas", hotel.getHotelName(), facturas.size());
            } catch (Exception e) {
                log.error("Error al procesar el hotel '{}': {}", hotel.getHotelName(), e.getMessage(), e);
            } finally {
                hotelContextService.clearCurrentHotel();
            }
        }

        log.info("Sincronización manual completada. Total de facturas creadas: {}", todas.size());
        return todas;
    }

    /**
     * Resultado agregado de la fase de reintentos.
     */
    private record ResumenHoteles(int exitosos, int fallidos, int facturas) {
    }

    private record ResumenReintentos(int exitosos, int fallidos) {
        int total() {
            return exitosos + fallidos;
        }
    }
}
