package co.com.siigo.integrations.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XmlResponseParserTest {

    private static final String CREDENCIALES_INVALIDAS = """
            <Response>
                <ServerInfo>
                    <Name>Hotelwiz-server</Name>
                    <ResponseTime>97 ms</ResponseTime>
                </ServerInfo>
                <ProcessCardStatus StatusCode="Z30" Description="Invalid agent credentials" />
            </Response>
            """;

    @Test
    void detectarError_credencialesInvalidas_devuelveCodigoYDescripcion() {
        var error = XmlResponseParser.detectarError(CREDENCIALES_INVALIDAS, "Balance");

        assertThat(error).hasValueSatisfying(e -> {
            assertThat(e.codigo()).isEqualTo("Z30");
            assertThat(e.descripcion()).isEqualTo("Invalid agent credentials");
        });
    }

    @Test
    void detectarError_conElementoEsperado_noEsError() {
        String xml = """
                <Response>
                    <ProcessCardStatus StatusCode="000" Description="OK" />
                    <Balance><ReservationNumber>070001108</ReservationNumber></Balance>
                </Response>
                """;

        assertThat(XmlResponseParser.detectarError(xml, "Balance")).isEmpty();
    }

    @Test
    void detectarError_sinProcessCardStatus_noEsError() {
        assertThat(XmlResponseParser.detectarError("<Response><Otro/></Response>", "Balance")).isEmpty();
    }

    @Test
    void detectarError_respuestaVacia_esError() {
        assertThat(XmlResponseParser.detectarError("", "Balance")).isPresent();
    }
}
