package co.com.siigo.integrations.web;

import co.com.siigo.integrations.dto.siigo.ClienteResponse;
import co.com.siigo.integrations.dto.siigo.FacturaResponse;
import co.com.siigo.integrations.service.ClienteService;
import co.com.siigo.integrations.service.HotelContextService;
import co.com.siigo.integrations.service.IntegrationService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Endpoints directos de integración, pensados para pruebas y diagnóstico.
 *
 * <p>Todos aceptan {@code ?hotelKey=}; si se omite y hay un solo hotel activo, se usa ese.
 * Con varios hoteles activos el parámetro es obligatorio.
 */
@RestController
@RequestMapping("/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService service;
    private final ClienteService clienteService;
    private final HotelContextService hotelContextService;

    @GetMapping("/minihotel/syncFactura/{reservationNumber}")
    public List<FacturaResponse> syncFactura(@PathVariable @NotBlank String reservationNumber,
                                             @RequestParam(required = false) String hotelKey) {
        return hotelContextService.ejecutarEnHotel(hotelKey,
                () -> service.syncFacturasASiigo(reservationNumber));
    }

    @GetMapping("/minihotel/syncFactura")
    public ResponseEntity<List<FacturaResponse>> syncFacturasPorRango(@RequestParam String fromDate,
                                                                      @RequestParam String toDate,
                                                                      @RequestParam(required = false) String hotelKey) {
        return ResponseEntity.ok(hotelContextService.ejecutarEnHotel(hotelKey,
                () -> service.syncFacturasASiigo(fromDate, toDate)));
    }

    /**
     * Consulta un tercero en Siigo por su número de identificación.
     */
    @GetMapping("/siigo/clientes/{identificacion}")
    public ClienteResponse buscarCliente(@PathVariable @NotBlank String identificacion,
                                         @RequestParam(required = false) String hotelKey) {
        return hotelContextService.ejecutarEnHotel(hotelKey,
                        () -> clienteService.buscarPorIdentificacion(identificacion))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado en Siigo"));
    }
}
