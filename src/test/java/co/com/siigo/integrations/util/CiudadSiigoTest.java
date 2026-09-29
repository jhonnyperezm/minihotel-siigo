package co.com.siigo.integrations.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CiudadSiigoTest {

    @Test
    void buscarPorNombre_coincidenciaExacta() {
        assertThat(CiudadSiigo.buscarPorNombre("Medellín")).contains(CiudadSiigo.MEDELLIN);
    }

    @Test
    void buscarPorNombre_ignoraTildesYMayusculas() {
        assertThat(CiudadSiigo.buscarPorNombre("MEDELLIN")).contains(CiudadSiigo.MEDELLIN);
        assertThat(CiudadSiigo.buscarPorNombre("bogota")).contains(CiudadSiigo.BOGOTA);
    }

    @Test
    void buscarPorNombre_reconoceAlias() {
        assertThat(CiudadSiigo.buscarPorNombre("Bogota D.C.")).contains(CiudadSiigo.BOGOTA);
        assertThat(CiudadSiigo.buscarPorNombre("Santiago de Cali")).contains(CiudadSiigo.CALI);
        assertThat(CiudadSiigo.buscarPorNombre("Cartagena de Indias")).contains(CiudadSiigo.CARTAGENA);
    }

    @Test
    void buscarPorNombre_desconocida_devuelveVacio() {
        assertThat(CiudadSiigo.buscarPorNombre("Springfield")).isEmpty();
        assertThat(CiudadSiigo.buscarPorNombre(null)).isEmpty();
        assertThat(CiudadSiigo.buscarPorNombre("  ")).isEmpty();
    }

    @Test
    void toCiudad_devuelveCodigosDane() {
        var ciudad = CiudadSiigo.MEDELLIN.toCiudad();

        assertThat(ciudad.countryCode()).isEqualTo("CO");
        assertThat(ciudad.stateCode()).isEqualTo("05");
        assertThat(ciudad.cityCode()).isEqualTo("05001");
    }

    @Test
    void resolver_ciudadDesconocida_usaLaPorDefecto() {
        var ciudad = CiudadSiigo.resolver("Ciudad Inventada", CiudadSiigo.BOGOTA);

        assertThat(ciudad.cityCode()).isEqualTo("11001");
    }

    @Test
    void resolver_ciudadConocida_ignoraLaPorDefecto() {
        var ciudad = CiudadSiigo.resolver("Envigado", CiudadSiigo.BOGOTA);

        assertThat(ciudad.cityCode()).isEqualTo("05266");
    }
}
