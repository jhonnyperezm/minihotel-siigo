package co.com.siigo.integrations.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtilTest {

    // ─── serializeToJson ───────────────────────────────────────────────────

    @Test
    void serializeToJson_devuelveJson() {
        var result = Util.serializeToJson(new Sample("hello", 42), new ObjectMapper());
        assertThat(result).contains("\"name\":\"hello\"").contains("\"value\":42");
    }

    @Test
    void serializeToJson_objetoNulo_devuelveNullLiteral() {
        assertThat(Util.serializeToJson(null, new ObjectMapper())).isEqualTo("null");
    }

    // ─── headers de Siigo ──────────────────────────────────────────────────

    @Test
    void siigoHeaders_incluyeBearerYPartnerId() {
        HttpHeaders headers = Util.siigoHeaders("abc123", "MiApp");

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer abc123");
        assertThat(headers.getFirst("Partner-Id")).isEqualTo("MiApp");
        assertThat(headers.getContentType()).hasToString("application/json");
    }

    @Test
    void siigoHeaders_sinToken_noIncluyeAuthorization() {
        HttpHeaders headers = Util.siigoAuthHeaders("MiApp");

        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(headers.getFirst("Partner-Id")).isEqualTo("MiApp");
    }

    @Test
    void siigoHeaders_tokenEnBlanco_noIncluyeAuthorization() {
        assertThat(Util.siigoHeaders("   ", "MiApp").getFirst(HttpHeaders.AUTHORIZATION)).isNull();
    }

    // ─── splitInTwo ────────────────────────────────────────────────────────

    @Test
    void splitInTwo_nombreCompuesto_separaEnDos() {
        assertThat(Util.splitInTwo("Juan Carlos")).containsExactly("Juan", "Carlos");
    }

    @Test
    void splitInTwo_nombreSimple_segundoEsNull() {
        assertThat(Util.splitInTwo("Maria")).containsExactly("Maria", null);
    }

    @Test
    void splitInTwo_null_devuelveAmbosNull() {
        assertThat(Util.splitInTwo(null)).containsExactly(null, null);
    }

    // ─── formatearFecha ────────────────────────────────────────────────────

    @Test
    void formatearFecha_yaEnFormatoSiigo_seDevuelveIgual() {
        assertThat(Util.formatearFecha("2026-03-15")).isEqualTo("2026-03-15");
    }

    @Test
    void formatearFecha_formatoLatino_seConvierte() {
        assertThat(Util.formatearFecha("15/03/2026")).isEqualTo("2026-03-15");
    }

    @Test
    void formatearFecha_formatoCompacto_seConvierte() {
        assertThat(Util.formatearFecha("20260315")).isEqualTo("2026-03-15");
    }

    @Test
    void formatearFecha_formatoNoSoportado_lanzaExcepcion() {
        assertThatThrownBy(() -> Util.formatearFecha("marzo 15"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no soportado");
    }

    @Test
    void formatearFecha_vacia_lanzaExcepcion() {
        assertThatThrownBy(() -> Util.formatearFecha("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── normalizarTexto / soloDigitos / truncar ───────────────────────────

    @Test
    void normalizarTexto_quitaTildes() {
        assertThat(Util.normalizarTexto("  Medellín ")).isEqualTo("Medellin");
    }

    @Test
    void normalizarTexto_null_devuelveVacio() {
        assertThat(Util.normalizarTexto(null)).isEmpty();
    }

    @Test
    void soloDigitos_eliminaSeparadores() {
        assertThat(Util.soloDigitos("900.123.456-7")).isEqualTo("9001234567");
    }

    @Test
    void truncar_recortaAlLimite() {
        assertThat(Util.truncar("abcdefghij", 4)).isEqualTo("abcd");
        assertThat(Util.truncar("abc", 10)).isEqualTo("abc");
        assertThat(Util.truncar(null, 5)).isNull();
    }

    // ─── digito de verificacion del NIT ────────────────────────────────────

    @Test
    void calcularDigitoVerificacion_nitConocido() {
        // NIT de ejemplo de la DIAN: 800197268 -> DV 4
        assertThat(Util.calcularDigitoVerificacion("800197268")).isEqualTo("4");
    }

    @Test
    void calcularDigitoVerificacion_ignoraPuntosYGuiones() {
        assertThat(Util.calcularDigitoVerificacion("800.197.268"))
                .isEqualTo(Util.calcularDigitoVerificacion("800197268"));
    }

    @Test
    void calcularDigitoVerificacion_vacio_devuelveNull() {
        assertThat(Util.calcularDigitoVerificacion("")).isNull();
        assertThat(Util.calcularDigitoVerificacion(null)).isNull();
    }

    private record Sample(String name, int value) {
    }
}
