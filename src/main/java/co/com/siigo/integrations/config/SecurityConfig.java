package co.com.siigo.integrations.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${integrations.security.username}")
    private String username;

    @Value("${integrations.security.password}")
    private String password;

    /**
     * Credenciales dedicadas para MiniHotel. Van aparte de las del panel para poder rotarlas
     * sin perder el acceso administrativo, y para que un compromiso del webhook no dé acceso
     * a los endpoints de sincronización.
     */
    @Value("${integrations.webhooks.username:}")
    private String webhookUsername;

    @Value("${integrations.webhooks.password:}")
    private String webhookPassword;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // H2 console solo en desarrollo (deshabilitado en producción via application.yml)
                .requestMatchers("/h2-console/**").permitAll()
                // Actuator health check sin autenticación
                .requestMatchers("/actuator/health").permitAll()
                // Frontend estático (carga sin auth; las llamadas API llevan Basic header)
                .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
                // Webhooks: accesible con las credenciales de MiniHotel o con las del panel
                .requestMatchers("/api/webhooks/minihotel").hasAnyRole("WEBHOOK", "API")
                .requestMatchers("/api/webhooks/minihotel/ping").hasAnyRole("WEBHOOK", "API")
                // El resto del módulo de webhooks (consulta y reproceso) es solo del panel
                .requestMatchers("/api/webhooks/**").hasRole("API")
                // Todo lo demás requiere el rol administrativo
                .anyRequest().hasRole("API")
            )
            .httpBasic(httpBasic -> {})
            // Necesario para que el iframe de H2 console funcione
            .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin));

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        var admin = User.builder()
                .username(username)
                .password(encoder.encode(password))
                .roles("API")
                .build();

        // El usuario de webhooks solo existe si se configuró; sin él, MiniHotel puede
        // autenticarse con las credenciales del panel (útil en desarrollo).
        if (webhookUsername != null && !webhookUsername.isBlank()
                && webhookPassword != null && !webhookPassword.isBlank()) {

            var webhook = User.builder()
                    .username(webhookUsername)
                    .password(encoder.encode(webhookPassword))
                    .roles("WEBHOOK")
                    .build();
            return new InMemoryUserDetailsManager(admin, webhook);
        }
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
