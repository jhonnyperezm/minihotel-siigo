package co.com.siigo.integrations.util;

import co.com.siigo.integrations.dto.siigo.CrearClienteRequest;
import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * Catálogo de ciudades en códigos país / departamento / municipio, que es como Siigo
 * identifica la ubicación de un tercero.
 *
 * <p>Reemplaza al catálogo {@code /ciudad/listarCiudades} de World Office: Siigo no expone
 * un endpoint de ciudades, sino que espera los códigos DANE directamente en el objeto
 * {@code address.city}. Por eso la tabla vive en el código y no en un caché remoto.
 *
 * <p>Incluye las capitales departamentales y los municipios donde opera la cadena.
 * La lista completa la publica Siigo en su documentación ("Lista de ciudades"); si se
 * necesita un municipio que no esté aquí, basta con agregarlo como una constante más.
 */
@Getter
public enum CiudadSiigo {

    // ── Capitales departamentales ───────────────────────────────────────────
    BOGOTA("11", "11001", "Bogotá D.C.", "Bogota", "Bogota D.C.", "Bogotá, D.C.", "BOG"),
    MEDELLIN("05", "05001", "Medellín", "Medellin", "MDE"),
    CALI("76", "76001", "Cali", "Santiago de Cali", "CLO"),
    BARRANQUILLA("08", "08001", "Barranquilla", "BAQ"),
    CARTAGENA("13", "13001", "Cartagena", "Cartagena de Indias", "CTG"),
    CUCUTA("54", "54001", "Cúcuta", "Cucuta", "San José de Cúcuta", "CUC"),
    BUCARAMANGA("68", "68001", "Bucaramanga", "BGA"),
    PEREIRA("66", "66001", "Pereira", "PEI"),
    MANIZALES("17", "17001", "Manizales", "MZL"),
    SANTA_MARTA("47", "47001", "Santa Marta", "SMR"),
    VILLAVICENCIO("50", "50001", "Villavicencio", "VVC"),
    IBAGUE("73", "73001", "Ibagué", "Ibague", "IBE"),
    ARMENIA("63", "63001", "Armenia", "AXM"),
    NEIVA("41", "41001", "Neiva", "NVA"),
    PASTO("52", "52001", "Pasto", "San Juan de Pasto", "PSO"),
    MONTERIA("23", "23001", "Montería", "Monteria", "MTR"),
    POPAYAN("19", "19001", "Popayán", "Popayan", "PPN"),
    SINCELEJO("70", "70001", "Sincelejo", "SIN"),
    VALLEDUPAR("20", "20001", "Valledupar", "VUP"),
    RIOHACHA("44", "44001", "Riohacha", "RCH"),
    TUNJA("15", "15001", "Tunja"),
    QUIBDO("27", "27001", "Quibdó", "Quibdo", "UIB"),
    FLORENCIA("18", "18001", "Florencia", "FLA"),
    YOPAL("85", "85001", "Yopal", "EYP"),
    MOCOA("86", "86001", "Mocoa"),
    LETICIA("91", "91001", "Leticia", "LET"),
    SAN_ANDRES("88", "88001", "San Andrés", "San Andres", "ADZ"),
    ARAUCA("81", "81001", "Arauca", "AUC"),
    PUERTO_CARRENO("99", "99001", "Puerto Carreño", "Puerto Carreno", "PCR"),
    INIRIDA("94", "94001", "Inírida", "Inirida", "PDA"),
    MITU("97", "97001", "Mitú", "Mitu", "MVP"),
    SAN_JOSE_GUAVIARE("95", "95001", "San José del Guaviare", "San Jose del Guaviare", "SJE"),

    // ── Área metropolitana y oriente antioqueño ─────────────────────────────
    ENVIGADO("05", "05266", "Envigado"),
    ITAGUI("05", "05360", "Itagüí", "Itagui"),
    BELLO("05", "05088", "Bello"),
    SABANETA("05", "05631", "Sabaneta"),
    LA_ESTRELLA("05", "05380", "La Estrella"),
    COPACABANA("05", "05212", "Copacabana"),
    GIRARDOTA("05", "05308", "Girardota"),
    CALDAS_ANT("05", "05129", "Caldas"),
    BARBOSA_ANT("05", "05079", "Barbosa"),
    RIONEGRO("05", "05615", "Rionegro"),
    GUARNE("05", "05318", "Guarne"),
    MARINILLA("05", "05440", "Marinilla"),
    EL_RETIRO("05", "05607", "El Retiro"),
    APARTADO("05", "05045", "Apartadó", "Apartado"),

    // ── Valle del Cauca ─────────────────────────────────────────────────────
    PALMIRA("76", "76520", "Palmira"),
    BUENAVENTURA("76", "76109", "Buenaventura"),
    TULUA("76", "76834", "Tuluá", "Tulua"),
    JAMUNDI("76", "76364", "Jamundí", "Jamundi"),
    YUMBO("76", "76892", "Yumbo"),

    // ── Atlántico ───────────────────────────────────────────────────────────
    SOLEDAD("08", "08758", "Soledad"),
    PUERTO_COLOMBIA("08", "08573", "Puerto Colombia"),
    MALAMBO("08", "08433", "Malambo"),

    // ── Santander ───────────────────────────────────────────────────────────
    FLORIDABLANCA("68", "68276", "Floridablanca"),
    GIRON("68", "68307", "Girón", "Giron"),
    PIEDECUESTA("68", "68547", "Piedecuesta"),
    BARRANCABERMEJA("68", "68081", "Barrancabermeja", "EJA"),
    SAN_GIL("68", "68679", "San Gil"),
    BARICHARA("68", "68079", "Barichara"),

    // ── Norte de Santander ──────────────────────────────────────────────────
    CHINACOTA("54", "54172", "Chinácota", "Chinacota"),
    PAMPLONA("54", "54518", "Pamplona"),
    OCANA("54", "54498", "Ocaña", "Ocana", "OCV"),
    VILLA_DEL_ROSARIO("54", "54874", "Villa del Rosario"),
    LOS_PATIOS("54", "54405", "Los Patios"),

    // ── Cundinamarca ────────────────────────────────────────────────────────
    SOACHA("25", "25754", "Soacha"),
    CHIA("25", "25175", "Chía", "Chia"),
    ZIPAQUIRA("25", "25899", "Zipaquirá", "Zipaquira"),
    GIRARDOT("25", "25307", "Girardot"),
    FUSAGASUGA("25", "25290", "Fusagasugá", "Fusagasuga"),
    FACATATIVA("25", "25269", "Facatativá", "Facatativa"),
    MOSQUERA("25", "25473", "Mosquera"),
    MADRID("25", "25430", "Madrid"),
    FUNZA("25", "25286", "Funza"),
    CAJICA("25", "25126", "Cajicá", "Cajica"),

    // ── Otros destinos frecuentes ───────────────────────────────────────────
    DOSQUEBRADAS("66", "66170", "Dosquebradas"),
    SANTA_ROSA_CABAL("66", "66682", "Santa Rosa de Cabal"),
    SOGAMOSO("15", "15759", "Sogamoso"),
    DUITAMA("15", "15238", "Duitama"),
    VILLA_DE_LEYVA("15", "15407", "Villa de Leyva", "Villa de Leiva"),
    PAIPA("15", "15516", "Paipa"),
    TURBACO("13", "13836", "Turbaco"),
    SALENTO("63", "63690", "Salento"),
    FILANDIA("63", "63272", "Filandia"),
    IPIALES("52", "52356", "Ipiales", "IPI"),
    TUMACO("52", "52835", "Tumaco", "TCO"),
    TOLU("70", "70820", "Santiago de Tolú", "Tolu", "Tolú"),
    COVENAS("70", "70221", "Coveñas", "Covenas"),
    CIENAGA("47", "47189", "Ciénaga", "Cienaga"),
    AGUACHICA("20", "20011", "Aguachica"),
    MELGAR("73", "73449", "Melgar"),
    ESPINAL("73", "73268", "Espinal"),
    PITALITO("41", "41551", "Pitalito"),
    ACACIAS("50", "50006", "Acacías", "Acacias");

    /** Código de país ISO usado por Siigo para Colombia. */
    public static final String PAIS_COLOMBIA = "CO";

    private final String countryCode;
    private final String stateCode;
    private final String cityCode;
    private final String nombreOficial;
    private final String[] alias;

    CiudadSiigo(String stateCode, String cityCode, String nombreOficial, String... alias) {
        this.countryCode = PAIS_COLOMBIA;
        this.stateCode = stateCode;
        this.cityCode = cityCode;
        this.nombreOficial = nombreOficial;
        this.alias = alias;
    }

    /**
     * Convierte la constante en el objeto {@code address.city} que espera Siigo.
     */
    public CrearClienteRequest.Ciudad toCiudad() {
        return new CrearClienteRequest.Ciudad(countryCode, stateCode, cityCode);
    }

    /**
     * Busca una ciudad por su nombre tal como llega de MiniHotel, ignorando tildes,
     * mayúsculas y espacios sobrantes.
     *
     * @param nombre nombre de la ciudad (puede ser null)
     * @return la ciudad si se reconoce, vacío en caso contrario
     */
    public static Optional<CiudadSiigo> buscarPorNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) return Optional.empty();
        String normalizado = Util.normalizarTexto(nombre);

        return Arrays.stream(values())
                .filter(c -> c.coincideCon(normalizado))
                .findFirst();
    }

    /**
     * Resuelve la ciudad de un huésped, con respaldo en la ciudad indicada por defecto.
     *
     * @param nombre      ciudad reportada por MiniHotel
     * @param porDefecto  ciudad a usar cuando no se reconoce el nombre
     */
    public static CrearClienteRequest.Ciudad resolver(String nombre, CiudadSiigo porDefecto) {
        return buscarPorNombre(nombre).orElse(porDefecto).toCiudad();
    }

    private boolean coincideCon(String nombreNormalizado) {
        if (Util.normalizarTexto(nombreOficial).equalsIgnoreCase(nombreNormalizado)) return true;
        return Arrays.stream(alias)
                .anyMatch(a -> Util.normalizarTexto(a).equalsIgnoreCase(nombreNormalizado));
    }
}
