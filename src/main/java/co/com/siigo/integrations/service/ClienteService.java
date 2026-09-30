package co.com.siigo.integrations.service;

import co.com.siigo.integrations.client.SiigoClienteClient;
import co.com.siigo.integrations.dto.minihotel.GuestResponse;
import co.com.siigo.integrations.dto.siigo.ClienteResponse;
import co.com.siigo.integrations.dto.siigo.CrearClienteRequest;
import co.com.siigo.integrations.dto.siigo.enums.ResponsabilidadFiscalEnum;
import co.com.siigo.integrations.dto.siigo.enums.TipoIdentificacionEnum;
import co.com.siigo.integrations.util.CiudadSiigo;
import co.com.siigo.integrations.util.Util;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.isNull;

/**
 * Gestión de terceros (clientes) en Siigo.
 *
 * <p>Reemplaza a {@code TerceroService}. La lógica de negocio es la misma —buscar el huésped
 * y crearlo si no existe— pero mucho más corta: Siigo no exige resolver catálogos de tipo de
 * contribuyente, responsabilidad fiscal ni ciudad por id. Se envían códigos fijos.
 *
 * <p>Otra diferencia: la factura de Siigo referencia al cliente por su <em>identificación</em>,
 * no por un id interno, así que este servicio devuelve la identificación y la sucursal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClienteService {

    private static final String DIRECCION_NO_INFORMADA = "No Informada";
    private static final int MAX_NOMBRE = 100;
    private static final int MAX_DIRECCION = 256;
    private static final int MAX_EMAIL = 100;
    private static final int MAX_TELEFONO = 10;
    private static final String TELEFONO_POR_DEFECTO = "11111111";
    private static final String EMAIL_POR_DEFECTO = "correo@noinformado.com";
    private static final int MIN_IDENTIFICACION = 5;

    private final SiigoClienteClient clienteClient;
    private final HotelContextService hotelContextService;

    /**
     * Devuelve la referencia al cliente en Siigo para el huésped dado, creándolo si no existe.
     *
     * <p>Si el huésped no trae número de identificación se usa el tercero genérico configurado
     * en {@code consumidor-final-identificacion}, que debe existir previamente en Siigo Nube.
     *
     * @param guest huésped principal de la reservación
     * @return identificación y sucursal con las que referenciar el cliente en la factura
     * @throws IllegalStateException si el consumidor final no existe o si Siigo rechaza la creación
     */
    public ClienteRef obtenerOCrearCliente(GuestResponse guest) {
        String identificacion = guest == null ? null : guest.getIdNumber();

        if (isNull(identificacion) || identificacion.isBlank()) {
            log.info("Reserva sin identificación: se factura al consumidor final");
            return consumidorFinal();
        }

        String normalizada = normalizarIdentificacion(identificacion);
        if (!esIdentificacionValida(normalizada)) {
            log.warn("Identificación '{}' de {} no es válida: se factura al consumidor final",
                    identificacion, guest.getFullName());
            return consumidorFinal();
        }

        Optional<ClienteResponse> existente = clienteClient.buscarPorIdentificacion(normalizada);
        if (existente.isPresent()) {
            log.debug("Cliente {} ya existe en Siigo", normalizada);
            return new ClienteRef(normalizada, existente.get().sucursal());
        }

        TipoIdentificacionEnum tipo = inferirTipoIdentificacion(identificacion, guest.getCountry());
        CrearClienteRequest request = construirRequest(guest, normalizada, tipo);

        ClienteResponse creado = clienteClient.crearCliente(request);
        if (isNull(creado) || isNull(creado.identification())) {
            throw new IllegalStateException(
                    "Siigo no devolvió el tercero creado para: " + guest.getFullName());
        }

        log.info("Cliente creado en Siigo: {} ({})", creado.nombreCompleto(), creado.identification());
        return new ClienteRef(creado.identification(), creado.sucursal());
    }

    private ClienteRef consumidorFinal() {
        var facturacion = hotelContextService.getFacturacion();
        String consumidorFinal = facturacion.getConsumidorFinalIdentificacion();
        clienteClient.buscarPorIdentificacion(consumidorFinal)
                .orElseThrow(() -> new IllegalStateException(
                        "No existe en Siigo el tercero consumidor final con identificación '"
                        + consumidorFinal + "'. Créalo en Siigo Nube o ajusta la configuración."));
        return new ClienteRef(consumidorFinal, facturacion.getSucursalCliente());
    }

    /**
     * Descarta identificaciones de relleno que la recepción digita para poder cerrar la reserva
     * ({@code 1}, {@code 0}, {@code 000000}, {@code 11111111}): son demasiado cortas o repiten
     * un único carácter. Crearlas en Siigo ensucia los terceros y la DIAN las rechaza.
     */
    boolean esIdentificacionValida(String normalizada) {
        return normalizada.length() >= MIN_IDENTIFICACION
                && !normalizada.matches("(.)\\1+");
    }

    /**
     * Busca un tercero por identificación. Se usa desde los endpoints de diagnóstico.
     */
    public Optional<ClienteResponse> buscarPorIdentificacion(String identificacion) {
        return clienteClient.buscarPorIdentificacion(identificacion);
    }

    /**
     * Infiere el tipo de documento, ya que MiniHotel no lo informa.
     *
     * <ol>
     *   <li>Formato {@code XXXXXXXXX-D} (dígitos, guion y dígito verificador) -&gt; NIT (31)</li>
     *   <li>Contiene letras -&gt; Pasaporte (41)</li>
     *   <li>País distinto de Colombia y solo dígitos -&gt; Cédula de extranjería (22)</li>
     *   <li>En cualquier otro caso -&gt; Cédula de ciudadanía (13)</li>
     * </ol>
     *
     * <p>Como Siigo exige que las identificaciones de tipo 13 y 31 sean numéricas de 3 a 13
     * dígitos, si el número no cumple se degrada a documento de identificación extranjero (42),
     * que admite alfanuméricos.
     */
    TipoIdentificacionEnum inferirTipoIdentificacion(String idNumber, String country) {
        if (idNumber == null || idNumber.isBlank()) {
            return TipoIdentificacionEnum.CEDULA_CIUDADANIA;
        }

        String sinSeparadores = idNumber.replaceAll("[\\s.-]", "");
        String conGuion = idNumber.replaceAll("[\\s.]", "");

        TipoIdentificacionEnum tipo;

        if (sinSeparadores.matches("[A-Za-z]\\d{6,9}") || !sinSeparadores.matches("\\d+")) {
            tipo = TipoIdentificacionEnum.PASAPORTE;
        } else if (esPaisExtranjero(country)) {
            tipo = TipoIdentificacionEnum.CEDULA_EXTRANJERIA;
        } else if (conGuion.matches("\\d{8,10}-\\d")) {
            tipo = TipoIdentificacionEnum.NIT;
        } else {
            tipo = TipoIdentificacionEnum.CEDULA_CIUDADANIA;
        }

        // Siigo restringe la longitud de CC y NIT: si no encaja, se usa un tipo alfanumérico.
        if ((tipo == TipoIdentificacionEnum.CEDULA_CIUDADANIA || tipo == TipoIdentificacionEnum.NIT)
                && !sinSeparadores.matches("\\d{3,13}")) {
            log.warn("La identificación '{}' no cumple el formato de {} en Siigo; se registrará como documento extranjero",
                    idNumber, tipo.getDescripcion());
            return TipoIdentificacionEnum.DOCUMENTO_EXTRANJERO;
        }

        return tipo;
    }

    private CrearClienteRequest construirRequest(GuestResponse guest, String identificacion,
                                                 TipoIdentificacionEnum tipo) {
        var facturacion = hotelContextService.getFacturacion();
        boolean esEmpresa = tipo.esEmpresa();

        var ciudadPorDefecto = CiudadSiigo.buscarPorNombre(facturacion.getCiudadPorDefecto())
                .orElse(CiudadSiigo.BOGOTA);

        return new CrearClienteRequest(
                "Customer",
                tipo.personType().getValor(),
                tipo.getCodigo(),
                identificacion,
                tipo == TipoIdentificacionEnum.NIT ? Util.calcularDigitoVerificacion(identificacion) : null,
                construirNombre(guest, esEmpresa),
                null,
                facturacion.getSucursalCliente(),
                true,
                esEmpresa,
                List.of(new CrearClienteRequest.ResponsabilidadFiscal(
                        ResponsabilidadFiscalEnum.NO_APLICA.getCodigo())),
                new CrearClienteRequest.Direccion(
                        direccion(guest.getAddress()),
                        CiudadSiigo.resolver(guest.getCity(), ciudadPorDefecto),
                        null),
                telefonos(guest.getPhone()),
                contactos(guest),
                "Creado automáticamente desde MiniHotel"
        );
    }

    /**
     * Para persona natural Siigo espera dos elementos (nombres y apellidos);
     * para persona jurídica, uno solo con la razón social.
     */
    private List<String> construirNombre(GuestResponse guest, boolean esEmpresa) {
        String nombres = Util.truncar(valorODefecto(guest.getGivenName(), "SIN NOMBRE"), MAX_NOMBRE);
        String apellidos = Util.truncar(valorODefecto(guest.getSurname(), "SIN APELLIDO"), MAX_NOMBRE);

        if (esEmpresa) {
            String razonSocial = Util.truncar((nombres + " " + apellidos).trim(), MAX_NOMBRE);
            return List.of(razonSocial);
        }
        return List.of(nombres, apellidos);
    }

    private List<CrearClienteRequest.Telefono> telefonos(String telefono) {
        String digitos = Util.truncar(Util.soloDigitos(telefono), MAX_TELEFONO);
        boolean invalido = digitos == null || digitos.isBlank()
                || telefono.chars().anyMatch(Character::isLetter);
        return List.of(new CrearClienteRequest.Telefono(null, invalido ? TELEFONO_POR_DEFECTO : digitos, null));
    }

    private List<CrearClienteRequest.Contacto> contactos(GuestResponse guest) {
        List<CrearClienteRequest.Contacto> contactos = new ArrayList<>();
        contactos.add(new CrearClienteRequest.Contacto(
                Util.truncar(valorODefecto(guest.getGivenName(), "SIN NOMBRE"), 50),
                Util.truncar(guest.getSurname(), 50),
                emailValido(guest.getEmail())
        ));
        return contactos;
    }

    /**
     * Siigo rechaza correos con espacios o caracteres extraños; ante la duda se envía el correo por defecto.
     */
    private String emailValido(String email) {
        if (email == null || email.isBlank()) return EMAIL_POR_DEFECTO;
        String limpio = email.trim();
        boolean valido = limpio.length() <= MAX_EMAIL && limpio.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
        return valido ? limpio : EMAIL_POR_DEFECTO;
    }

    private String direccion(String direccion) {
        return direccion == null || direccion.isBlank()
                ? DIRECCION_NO_INFORMADA
                : Util.truncar(direccion, MAX_DIRECCION);
    }

    /**
     * Siigo no admite caracteres especiales en el número de identificación.
     * Para NIT se descarta el dígito de verificación, que viaja en su propio campo.
     */
    private String normalizarIdentificacion(String identificacion) {
        String limpia = identificacion.trim().toUpperCase();
        if (limpia.replaceAll("[\\s.]", "").matches("\\d{8,10}-\\d")) {
            return Util.soloDigitos(limpia.substring(0, limpia.lastIndexOf('-')));
        }
        return limpia.replaceAll("[\\s.-]", "");
    }

    private boolean esPaisExtranjero(String country) {
        return country != null && !country.isBlank()
                && !country.equalsIgnoreCase("Colombia")
                && !country.equalsIgnoreCase("CO");
    }

    private String valorODefecto(String valor, String porDefecto) {
        return valor == null || valor.isBlank() ? porDefecto : valor.trim();
    }

    /**
     * Referencia al cliente tal como la espera la factura de Siigo.
     *
     * @param identificacion número de identificación del tercero
     * @param sucursal       sucursal del tercero (0 por defecto)
     */
    public record ClienteRef(String identificacion, Integer sucursal) {
    }
}
