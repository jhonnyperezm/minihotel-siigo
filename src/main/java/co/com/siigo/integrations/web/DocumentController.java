package co.com.siigo.integrations.web;

import co.com.siigo.integrations.client.MiniHotelClient;
import co.com.siigo.integrations.dto.minihotel.GetDocumentResponse;
import co.com.siigo.integrations.service.HotelContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints para consultar documentos adjuntos a reservaciones en MiniHotel.
 * Retorna la URL de descarga (src), descripción e ID de cada documento.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hotel/{hotelKey}/reservations/{rsNumber}/documents")
public class DocumentController {

    private final MiniHotelClient miniHotelClient;
    private final HotelContextService hotelContextService;

    @GetMapping
    public ResponseEntity<List<GetDocumentResponse>> getDocuments(
            @PathVariable String hotelKey,
            @PathVariable String rsNumber) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelClient.getDocuments(rsNumber));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }

    @GetMapping(value = "/raw", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getDocumentsRaw(
            @PathVariable String hotelKey,
            @PathVariable String rsNumber) {
        try {
            hotelContextService.setCurrentHotel(hotelKey);
            return ResponseEntity.ok(miniHotelClient.getDocumentsRaw(rsNumber));
        } finally {
            hotelContextService.clearCurrentHotel();
        }
    }
}
