package co.com.siigo.integrations.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static java.util.Objects.nonNull;

@Slf4j
@RequiredArgsConstructor
public class Util {

    public static final ZoneId ZONA_BOGOTA = ZoneId.of("America/Bogota");

    /** Pesos del algoritmo de dígito de verificación del NIT colombiano (DIAN). */
    private static final int[] PESOS_DV = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};

    public static LocalDate hoyEnBogota() {
        return LocalDate.now(ZONA_BOGOTA);
    }

    /**
     * Serializa un objeto a JSON. Se usa para guardar en la auditoría el payload
     * exacto que se envió a Siigo.
     *
     * @return representación JSON del objeto, o null si ocurre un error
     */
    public static String serializeToJson(Object obj, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Error al serializar objeto a JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Construye los headers de autenticación de Siigo API.
     *
     * <p>Siigo exige el token JWT como Bearer y, desde 2025, el header {@code Partner-Id}
     * asociado a una aplicación registrada.
     *
     * @param token     token de acceso obtenido en {@code POST /auth}
     * @param partnerId identificador de la aplicación registrada en Siigo Nube
     */
    public static HttpHeaders siigoHeaders(String token, String partnerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (nonNull(token) && !token.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }
        if (nonNull(partnerId) && !partnerId.isBlank()) {
            headers.set("Partner-Id", partnerId.trim());
        }
        return headers;
    }

    /**
     * Headers sin token, para la propia petición de autenticación.
     */
    public static HttpHeaders siigoAuthHeaders(String partnerId) {
        return siigoHeaders(null, partnerId);
    }

    /**
     * Divide una cadena en dos partes separadas por espacios.
     * Útil para separar nombres y apellidos compuestos.
     * <pre>
     * "Juan Carlos" -&gt; ["Juan", "Carlos"]
     * "María"       -&gt; ["María", null]
     * null          -&gt; [null, null]
     * </pre>
     */
    public static String[] splitInTwo(String value) {
        if (value == null || value.isBlank()) {
            return new String[]{null, null};
        }
        String[] parts = value.trim().split("\\s+", 2);
        String second = parts.length > 1 ? parts[1] : null;
        return new String[]{parts[0], second};
    }

    /**
     * Formatea una fecha al formato {@code yyyy-MM-dd} que exige Siigo.
     *
     * @throws IllegalArgumentException si la fecha es nula, vacía o de formato no soportado
     */
    public static String formatearFecha(String fecha) {
        if (fecha == null || fecha.isBlank()) {
            throw new IllegalArgumentException("La fecha no puede estar vacía");
        }

        if (fecha.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return fecha;
        }

        if (fecha.matches("\\d{2}/\\d{2}/\\d{4}")) {
            String[] parts = fecha.split("/");
            return String.format("%s-%s-%s", parts[2], parts[1], parts[0]);
        }

        if (fecha.matches("\\d{8}")) {
            return fecha.substring(0, 4) + "-" + fecha.substring(4, 6) + "-" + fecha.substring(6, 8);
        }

        throw new IllegalArgumentException(
                "Formato de fecha no soportado: " + fecha + ". Formatos esperados: yyyy-MM-dd, dd/MM/yyyy o yyyyMMdd");
    }

    /**
     * Quita tildes y espacios sobrantes para poder comparar nombres de catálogos
     * (ciudades, medios de pago, impuestos) sin depender de la escritura exacta.
     */
    public static String normalizarTexto(String texto) {
        if (texto == null) return "";
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .trim();
    }

    /**
     * Deja únicamente los dígitos de una cadena.
     * Siigo no admite caracteres especiales en identificaciones ni teléfonos.
     */
    public static String soloDigitos(String valor) {
        return valor == null ? "" : valor.replaceAll("\\D", "");
    }

    /**
     * Recorta una cadena a la longitud máxima admitida por el campo de Siigo.
     */
    public static String truncar(String valor, int maxLength) {
        if (valor == null) return null;
        String limpio = valor.trim();
        return limpio.length() <= maxLength ? limpio : limpio.substring(0, maxLength);
    }

    /**
     * Calcula el dígito de verificación de un NIT colombiano según el algoritmo de la DIAN.
     *
     * @param nit número de NIT (se ignoran puntos, guiones y espacios)
     * @return el dígito de verificación, o null si el NIT no es válido
     */
    public static String calcularDigitoVerificacion(String nit) {
        String limpio = soloDigitos(nit);
        if (limpio.isEmpty() || limpio.length() > PESOS_DV.length) return null;

        int suma = 0;
        for (int i = 0; i < limpio.length(); i++) {
            int digito = Character.getNumericValue(limpio.charAt(limpio.length() - 1 - i));
            suma += digito * PESOS_DV[i];
        }

        int residuo = suma % 11;
        int dv = residuo < 2 ? residuo : 11 - residuo;
        return String.valueOf(dv);
    }
}
