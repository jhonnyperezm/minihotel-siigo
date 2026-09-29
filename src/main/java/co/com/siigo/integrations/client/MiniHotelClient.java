package co.com.siigo.integrations.client;

import co.com.siigo.integrations.config.IntegrationProperties;
import co.com.siigo.integrations.dto.minihotel.BookingResponse;
import co.com.siigo.integrations.dto.minihotel.GetDocumentResponse;
import co.com.siigo.integrations.dto.minihotel.ReservationBalanceResponse;
import co.com.siigo.integrations.dto.minihotel.enums.ReservationStatusEnum;
import co.com.siigo.integrations.service.HotelContextService;
import co.com.siigo.integrations.util.XmlResponseParser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static co.com.siigo.integrations.util.XmlResponseParser.parseBalanceResponse;
import static co.com.siigo.integrations.util.XmlResponseParser.tag;

@Service
@RequiredArgsConstructor
public class MiniHotelClient {

    @Value("${integrations.minihotel.base-url}")
    private String apiUrl;

    private final RestTemplate miniHotelRestTemplate;
    private final HotelContextService hotelContextService;


    public String getReservationByEmail(String email) {
        String xmlRequest = buildGetReservationKeyRequest(email, null, null, null, null);
        return sendXmlRequest(xmlRequest);
    }

    public String getReservationByPassport(String passportNumber) {
        String xmlRequest = buildGetReservationKeyRequest(null, passportNumber, null, null, null);
        return sendXmlRequest(xmlRequest);
    }

    public Optional<BookingResponse> getReservationByMinihotelId(String minihotelReservationId) {
        String xmlRequest = buildGetReservationKeyRequest(null, null, minihotelReservationId, null, null);
        return XmlResponseParser.parseBookingsResponse(sendXmlRequest(xmlRequest)).stream().findAny();
    }

    public String getReservationByMinihotelIdRaw(String minihotelReservationId) {
        return sendXmlRequest(buildGetReservationKeyRequest(null, null, minihotelReservationId, null, null));
    }

    public String getReservationByNameAndDate(String givenName, String surname, String arrivalDate) {
        String xmlRequest = buildGetReservationKeyRequestWithNameAndDate(givenName, surname, arrivalDate);
        return sendXmlRequest(xmlRequest);
    }

//    public List<BookingResponse> getReservationsByDateRange(String fromDate, String toDate) {
//        String xmlRequest = buildGetReservationKeyRequestByDateRange(fromDate, toDate);
//        return XmlResponseParser.parseBookingsResponse(sendXmlRequest(xmlRequest)).stream()
//                .filter(f -> ReservationStatusEnum.fromCode(f.getStatus()).equals(ReservationStatusEnum.OUT))
//                .toList();
//    }

    public List<BookingResponse> getReservationsByDateRange(String fromDate, String toDate) {
        String xmlRequest = buildGetReservationKeyRequestByDateRange(fromDate, toDate);
        return XmlResponseParser.parseBookingsResponse(sendXmlRequest(xmlRequest)).stream()
                .filter(f -> ReservationStatusEnum.fromCode(f.getStatus()).equals(ReservationStatusEnum.OUT))
                .toList();
//        return sendXmlRequest(xmlRequest);
    }

    public List<BookingResponse> getReservationsByDepartureDate(String fromDate, String toDate) {
        String xmlRequest = buildGetReservationKeyRequestByDepartureDate(fromDate, toDate);
        return XmlResponseParser.parseBookingsResponse(sendXmlRequest(xmlRequest));
    }

    public String getReservationByRoomAndStatus(String roomNumber, String status, String fromDate, String toDate) {
        String xmlRequest = buildGetReservationKeyRequestByRoomAndStatus(roomNumber, status, fromDate, toDate);
        return sendXmlRequest(xmlRequest);
    }

    private String buildGetReservationKeyRequest(String email, String passportNumber, String minihotelReservationId,
                                                 String portalReservationId, String ccNumber) {

        StringBuilder xml = new StringBuilder();

        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<GetReservationKey>");

        xml.append(buildAuthentication());
        StringBuilder bookingAttrs = new StringBuilder();

        if (email != null && !email.isEmpty())
            bookingAttrs.append("Email=\"").append(email).append("\" ");

        if (passportNumber != null && !passportNumber.isEmpty())
            bookingAttrs.append("PassportNumber=\"").append(passportNumber).append("\" ");

        if (minihotelReservationId != null && !minihotelReservationId.isEmpty())
            bookingAttrs.append("Minihotel_reservation_id=\"")
                    .append(minihotelReservationId)
                    .append("\" ");

        if (portalReservationId != null && !portalReservationId.isEmpty())
            bookingAttrs.append("ReservationNumber=\"")
                    .append(portalReservationId)
                    .append("\" ");

        if (ccNumber != null && !ccNumber.isEmpty())
            bookingAttrs.append("CCNumber=\"").append(ccNumber).append("\" ");

        xml.append(tag("BookingSearch", bookingAttrs.toString().trim()));
        xml.append("</GetReservationKey>");

        return xml.toString();
    }


    private String buildGetReservationKeyRequestWithNameAndDate(String givenName,
                                                                String surname, String arrivalDate) {

        StringBuilder xml = new StringBuilder();

        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<GetReservationKey>");

        xml.append(buildAuthentication());

        StringBuilder bookingAttrs = new StringBuilder();

        if (arrivalDate != null && !arrivalDate.isEmpty())
            bookingAttrs.append("ArrivalDate=\"").append(arrivalDate).append("\" ");

        if (givenName != null && !givenName.isEmpty())
            bookingAttrs.append("GivenName=\"").append(givenName).append("\" ");

        if (surname != null && !surname.isEmpty())
            bookingAttrs.append("Surname=\"").append(surname).append("\" ");

        xml.append(tag("BookingSearch", bookingAttrs.toString().trim()));
        xml.append("</GetReservationKey>");

        return xml.toString();
    }


    private String buildGetReservationKeyRequestByDateRange(String fromDate, String toDate) {

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<GetReservationKey>" +
                buildAuthentication() +
                tag("CreateDate",
                        "From=\"" + fromDate + "\" To=\"" + toDate + "\"") +
                "</GetReservationKey>";
    }


    private String buildGetReservationKeyRequestByDepartureDate(String fromDate, String toDate) {

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<GetReservationKey>" +
                buildAuthentication() +
                tag("DepartureDate",
                        "From=\"" + fromDate + "\" To=\"" + toDate + "\"") +
                tag("BookingSearch",
                        "Status=\"" + ReservationStatusEnum.OUT.getCode() + "\"") +
                "</GetReservationKey>";
    }


    private String buildGetReservationKeyRequestByRoomAndStatus(String roomNumber, String status,
                                                                String fromDate, String toDate) {

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<GetReservationKey>" +
                buildAuthentication() +
                tag("ArrivalDate",
                        "From=\"" + fromDate + "\" To=\"" + toDate + "\"") +
                tag("BookingSearch",
                        "roomNumber=\"" + roomNumber + "\" Status=\"" + status + "\"") +
                "</GetReservationKey>";
    }


    /**
     * Obtiene los documentos adjuntos a una reservación (PDF, imágenes, etc.).
     * Retorna la URL de descarga, descripción e ID de cada documento.
     */
    public List<GetDocumentResponse> getDocuments(String rsNumber) {
        return XmlResponseParser.parseGetDocumentsResponse(sendSciXmlRequest(buildGetDocumentsRequest(rsNumber)));
    }

    public String getDocumentsRaw(String rsNumber) {
        return sendSciXmlRequest(buildGetDocumentsRequest(rsNumber));
    }

    private String buildGetDocumentsRequest(String rsNumber) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Request>" +
                "<SCI name=\"sci_getDocuments\">" +
                buildAuthentication() +
                "<rs_number>" + rsNumber + "</rs_number>" +
                "</SCI>" +
                "</Request>";
    }

    /**
     * Balance de cargos de la reservación.
     *
     * @throws MiniHotelException si MiniHotel responde con un error en lugar del balance. Sin esta
     *                            verificación, un error como {@code Z30} se leería como un balance
     *                            vacío y la factura saldría sin renglones.
     */
    public ReservationBalanceResponse getReservationBalance(String minihotelReservationId) {
        String xmlRequest = buildGetReservationBalanceRequest(minihotelReservationId);
        String xmlResponse = sendBalanceXmlRequest(xmlRequest);

        XmlResponseParser.detectarError(xmlResponse, "Balance").ifPresent(error -> {
            throw new MiniHotelException("MiniHotel no devolvió el balance de la reserva "
                    + minihotelReservationId + ": " + error.codigo() + " " + error.descripcion()
                    + ("Z30".equalsIgnoreCase(error.codigo())
                        ? ". Revise usuario, clave y hotel-id en minihotel-auth." : ""),
                    200, null);
        });
        return parseBalanceResponse(xmlResponse);
    }

    public String getReservationBalanceRaw(String minihotelReservationId) {
        return sendBalanceXmlRequest(buildGetReservationBalanceRequest(minihotelReservationId));
    }

    private String buildGetReservationBalanceRequest(String reservationNumber) {
        return "<Request>" +
                "<Payment language=\"ENG\">" +
                buildAuthentication() +
                "<ReservationNumber>" + reservationNumber + "</ReservationNumber>" +
                "</Payment>" +
                "</Request>";
    }

    /**
     * Credenciales del bloque {@code minihotel-auth} del hotel activo. Sin hotel en el contexto
     * se falla en lugar de enviar credenciales vacías, que MiniHotel rechaza con un error poco claro.
     */
    private String buildAuthentication() {
        IntegrationProperties.HotelConfig.MiniHotelAuth auth =
                hotelContextService.getCurrentHotel().getMinihotelAuth();

        return tag("Authentication",
                "username=\"" + auth.getUsername() + "\" " +
                        "password=\"" + auth.getPassword() + "\"")
                + tag("Hotel",
                "id=\"" + auth.getHotelId() + "\"");
    }

    private String sendXmlRequest(String xmlRequest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        HttpEntity<String> request = new HttpEntity<>(xmlRequest, headers);

        ResponseEntity<String> response = miniHotelRestTemplate.postForEntity(
                apiUrl + "api/Agents/Sci/Reservation/GetReservationKey",
                request,
                String.class
        );

        return response.getBody();
    }

    private String sendSciXmlRequest(String xmlRequest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        HttpEntity<String> request = new HttpEntity<>(xmlRequest, headers);

        ResponseEntity<String> response = miniHotelRestTemplate.postForEntity(
                apiUrl + "/agents/ws/sci/sciMain.asmx/getDocuments",
                request,
                String.class
        );

        return response.getBody();
    }

    private String sendBalanceXmlRequest(String xmlRequest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);

        HttpEntity<String> request = new HttpEntity<>(xmlRequest, headers);

        ResponseEntity<String> response = miniHotelRestTemplate.postForEntity(
                apiUrl + "/agents/ws/sci/sciMain.asmx/GetReservationBalance",
                request,
                String.class
        );

        return response.getBody();
    }
}