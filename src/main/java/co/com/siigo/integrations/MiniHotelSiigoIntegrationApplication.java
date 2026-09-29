package co.com.siigo.integrations;

import co.com.siigo.integrations.config.IntegrationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Clase principal de la aplicación de integración MiniHotel - World Office.
 * Habilita las tareas programadas para sincronización automática diaria.
 * Habilita métodos asíncronos para operaciones que no requieren esperar respuesta.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(IntegrationProperties.class)
public class MiniHotelSiigoIntegrationApplication {

  public static void main(String[] args) {
    SpringApplication.run(MiniHotelSiigoIntegrationApplication.class, args);
  }
}
