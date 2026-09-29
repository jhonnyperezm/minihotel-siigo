package co.com.siigo.integrations.repository;

import co.com.siigo.integrations.entity.WebhookEvent;
import co.com.siigo.integrations.entity.WebhookEvent.EstadoWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    /** Consulta de idempotencia: la llave real es el hotel más el id de notificación. */
    Optional<WebhookEvent> findByHotelCodeAndNotificationId(String hotelCode, Long notificationId);

    List<WebhookEvent> findAllByOrderByRecibidoEnDesc();

    List<WebhookEvent> findByEstadoOrderByRecibidoEnDesc(EstadoWebhook estado);

    List<WebhookEvent> findByReservationNumberOrderByRecibidoEnDesc(String reservationNumber);

    List<WebhookEvent> findByRecibidoEnBetweenOrderByRecibidoEnDesc(LocalDateTime desde, LocalDateTime hasta);

    /** Eventos antiguos, para la limpieza periódica de la tabla. */
    List<WebhookEvent> findByRecibidoEnBefore(LocalDateTime limite);
}
