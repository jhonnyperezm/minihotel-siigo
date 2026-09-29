package co.com.siigo.integrations.util;


import co.com.siigo.integrations.dto.minihotel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Clase de utilidad para parsear la respuesta XML de MiniHotel
 * Esto es opcional si quieres trabajar con objetos Java en lugar de XML string
 */

public class XmlResponseParser {

    private static final Logger log = LoggerFactory.getLogger(XmlResponseParser.class);

    /**
     * Crea un DocumentBuilder seguro contra ataques XXE (XML External Entity).
     */
    private static DocumentBuilder createSafeDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    /** Estado que MiniHotel informa en {@code <ProcessCardStatus StatusCode=".." Description=".."/>}. */
    public record EstadoMiniHotel(String codigo, String descripcion) {
    }

    /**
     * Detecta la respuesta de error de MiniHotel: trae {@code ProcessCardStatus} y le falta el
     * elemento con los datos pedidos. Ocurre, por ejemplo, con credenciales inválidas
     * ({@code Z30 Invalid agent credentials}), que llegan con HTTP 200.
     *
     * <p>Si el elemento esperado está presente, la respuesta se considera válida aunque también
     * traiga un {@code ProcessCardStatus}.
     *
     * @return el estado de error, o vacío si la respuesta trae los datos esperados
     */
    public static Optional<EstadoMiniHotel> detectarError(String xmlResponse, String elementoEsperado) {
        if (xmlResponse == null || xmlResponse.isBlank()) {
            return Optional.of(new EstadoMiniHotel("", "Respuesta vacía"));
        }
        try {
            Document doc = createSafeDocumentBuilder()
                    .parse(new ByteArrayInputStream(xmlResponse.getBytes(StandardCharsets.UTF_8)));

            if (doc.getElementsByTagName(elementoEsperado).getLength() > 0) {
                return Optional.empty();
            }
            NodeList estados = doc.getElementsByTagName("ProcessCardStatus");
            if (estados.getLength() == 0) {
                return Optional.empty();
            }
            Element estado = (Element) estados.item(0);
            return Optional.of(new EstadoMiniHotel(estado.getAttribute("StatusCode"), estado.getAttribute("Description")));
        } catch (Exception e) {
            // Si no se puede leer, que lo reporte el parser específico con su propio mensaje
            return Optional.empty();
        }
    }

    public static String tag(String name, String attrs) {
        return "<" + name + " " + attrs + " />";
    }


    public static List<BookingResponse> parseBookingsResponse(String xmlResponse) {
        List<BookingResponse> bookings = new ArrayList<>();

        try {
            DocumentBuilder builder = createSafeDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlResponse.getBytes(StandardCharsets.UTF_8)));

            doc.getDocumentElement().normalize();

            NodeList bookingList = doc.getElementsByTagName("Booking");

            for (int i = 0; i < bookingList.getLength(); i++) {
                Node bookingNode = bookingList.item(i);

                if (bookingNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element bookingElement = (Element) bookingNode;
                    BookingResponse booking = new BookingResponse();

                    // Parsear atributos
                    booking.setPortalReservationId(bookingElement.getAttribute("Portal_reservation_id"));
                    booking.setMinihotelReservationId(bookingElement.getAttribute("Minihotel_reservation_id"));
                    booking.setStatus(bookingElement.getAttribute("Status"));
                    booking.setSource(bookingElement.getAttribute("source"));
                    booking.setCreateDateTime(bookingElement.getAttribute("createDateTime"));
                    booking.setArrivalTime(bookingElement.getAttribute("arrival_time"));
                    booking.setDepartureTime(bookingElement.getAttribute("departure_time"));

                    // Parsear PrimaryGuest
                    NodeList primaryGuestList = bookingElement.getElementsByTagName("PrimaryGuest");
                    if (primaryGuestList.getLength() > 0) {
                        Element primaryGuestElement = (Element) primaryGuestList.item(0);
                        GuestResponse primaryGuest = parseGuest(primaryGuestElement);
                        booking.setPrimaryGuest(primaryGuest);
                    }

                    // Parsear ResGlobalInfo
                    NodeList resGlobalInfoList = bookingElement.getElementsByTagName("ResGlobalInfo");
                    if (resGlobalInfoList.getLength() > 0) {
                        Element resGlobalInfoElement = (Element) resGlobalInfoList.item(0);
                        ResGlobalInfoResponse resGlobalInfo = parseResGlobalInfo(resGlobalInfoElement);
                        booking.setResGlobalInfo(resGlobalInfo);
                    }

                    bookings.add(booking);
                }
            }

        } catch (Exception e) {
            log.error("Error al parsear respuesta XML de reservaciones: {}", e.getMessage(), e);
            throw new RuntimeException("Error al parsear respuesta XML de MiniHotel", e);
        }

        return bookings;
    }

    private static GuestResponse parseGuest(Element guestElement) {
        GuestResponse guest = new GuestResponse();

        NodeList nameList = guestElement.getElementsByTagName("Name");
        if (nameList.getLength() > 0) {
            Element nameElement = (Element) nameList.item(0);
            guest.setGivenName(nameElement.getAttribute("givenName"));
            guest.setSurname(nameElement.getAttribute("surname"));
        }

        NodeList addressList = guestElement.getElementsByTagName("Address");
        if (addressList.getLength() > 0) {
            Element nameElement = (Element) addressList.item(0);
            guest.setAddress(nameElement.getAttribute("Street"));
            guest.setZip(nameElement.getAttribute("Zip"));
            guest.setCity(nameElement.getAttribute("City"));
        }

        NodeList countryList = guestElement.getElementsByTagName("Country");
        if (countryList.getLength() > 0) {
            Element nameElement = (Element) countryList.item(0);
            guest.setCountry(nameElement.getAttribute("CountryName"));
        }

        NodeList emailList = guestElement.getElementsByTagName("Email");
        if (emailList.getLength() > 0) {
            guest.setEmail(emailList.item(0).getTextContent());
        }

        NodeList phoneList = guestElement.getElementsByTagName("Phone");
        if (phoneList.getLength() > 0) {
            guest.setPhone(phoneList.item(0).getTextContent());
        }

        NodeList idNumberList = guestElement.getElementsByTagName("IdNumber");
        if (idNumberList.getLength() > 0) {
            guest.setIdNumber(idNumberList.item(0).getTextContent());
        }

        return guest;
    }

    private static ResGlobalInfoResponse parseResGlobalInfo(Element resGlobalInfoElement) {
        ResGlobalInfoResponse info = new ResGlobalInfoResponse();

        NodeList timespanList = resGlobalInfoElement.getElementsByTagName("Timespan");
        if (timespanList.getLength() > 0) {
            Element timespanElement = (Element) timespanList.item(0);
            info.setArrival(timespanElement.getAttribute("arrival"));
            info.setDeparture(timespanElement.getAttribute("departure"));
        }

        NodeList totalList = resGlobalInfoElement.getElementsByTagName("Total");
        if (totalList.getLength() > 0) {
            Element totalElement = (Element) totalList.item(0);
            info.setAmountAfterTaxes(totalElement.getAttribute("AmountAfterTaxes"));
            info.setCurrencyCode(totalElement.getAttribute("CurrencyCode"));
        }

        return info;
    }

    public static ReservationBalanceResponse parseBalanceResponse(String xmlResponse) {
        ReservationBalanceResponse balance = new ReservationBalanceResponse();

        try {
            DocumentBuilder builder = createSafeDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlResponse.getBytes(StandardCharsets.UTF_8)));

            doc.getDocumentElement().normalize();

            // Obtener el nodo Balance
            NodeList balanceList = doc.getElementsByTagName("Balance");

            if (balanceList.getLength() > 0) {
                Element balanceElement = (Element) balanceList.item(0);

                // Parsear información básica
                NodeList reservationNumberList = balanceElement.getElementsByTagName("ReservationNumber");
                if (reservationNumberList.getLength() > 0) {
                    balance.setReservationNumber(reservationNumberList.item(0).getTextContent());
                }

                NodeList currencyList = balanceElement.getElementsByTagName("Currency");
                if (currencyList.getLength() > 0) {
                    balance.setCurrency(currencyList.item(0).getTextContent());
                }

                NodeList debitList = balanceElement.getElementsByTagName("Debit");
                if (debitList.getLength() > 0) {
                    balance.setDebit(Double.parseDouble(debitList.item(0).getTextContent()));
                }

                NodeList creditList = balanceElement.getElementsByTagName("Credit");
                if (creditList.getLength() > 0) {
                    balance.setCredit(Double.parseDouble(creditList.item(0).getTextContent()));
                }

                NodeList totalDebitList = balanceElement.getElementsByTagName("TotalDebit");
                if (totalDebitList.getLength() > 0) {
                    balance.setTotalDebit(Double.parseDouble(totalDebitList.item(0).getTextContent()));
                }

                // Parsear transacciones
                NodeList transactionList = balanceElement.getElementsByTagName("Transaction");
                List<TransactionResponse> transactions = new ArrayList<>();

                for (int i = 0; i < transactionList.getLength(); i++) {
                    Node transactionNode = transactionList.item(i);

                    if (transactionNode.getNodeType() == Node.ELEMENT_NODE) {
                        Element transactionElement = (Element) transactionNode;
                        TransactionResponse transaction = parseTransaction(transactionElement);
                        transactions.add(transaction);
                    }
                }

                balance.setTransactions(transactions);
            }

        } catch (Exception e) {
            log.error("Error al parsear respuesta XML del balance de reservación: {}", e.getMessage(), e);
            throw new RuntimeException("Error al parsear balance XML de MiniHotel", e);
        }

        return balance;
    }

    public static List<GetDocumentResponse> parseGetDocumentsResponse(String xmlResponse) {
        List<GetDocumentResponse> results = new ArrayList<>();

        try {
            DocumentBuilder builder = createSafeDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlResponse.getBytes(StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            NodeList documentList = doc.getElementsByTagName("Document");
            for (int i = 0; i < documentList.getLength(); i++) {
                Node node = documentList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element el = (Element) node;
                    GetDocumentResponse res = new GetDocumentResponse();
                    res.setSrc(el.getAttribute("src"));
                    res.setDescription(el.getAttribute("description"));
                    res.setId(el.getAttribute("id"));
                    results.add(res);
                }
            }

        } catch (Exception e) {
            log.error("Error al parsear respuesta XML de getDocuments: {}", e.getMessage(), e);
            throw new RuntimeException("Error al parsear respuesta XML de getDocuments", e);
        }

        return results;
    }

    private static TransactionResponse parseTransaction(Element transactionElement) {
        TransactionResponse transaction = new TransactionResponse();

        NodeList accountList = transactionElement.getElementsByTagName("Account");
        if (accountList.getLength() > 0) {
            transaction.setAccount(accountList.item(0).getTextContent());
        }

        NodeList dateList = transactionElement.getElementsByTagName("Date");
        if (dateList.getLength() > 0) {
            transaction.setDate(dateList.item(0).getTextContent());
        }

        NodeList timeList = transactionElement.getElementsByTagName("Time");
        if (timeList.getLength() > 0) {
            transaction.setTime(timeList.item(0).getTextContent());
        }

        NodeList departmentList = transactionElement.getElementsByTagName("Department");
        if (departmentList.getLength() > 0) {
            transaction.setDepartment(departmentList.item(0).getTextContent());
        }

        NodeList debitCreditList = transactionElement.getElementsByTagName("DebitCredit");
        if (debitCreditList.getLength() > 0) {
            transaction.setDebitCredit(Integer.parseInt(debitCreditList.item(0).getTextContent()));
        }

        NodeList detailsList = transactionElement.getElementsByTagName("Details");
        if (detailsList.getLength() > 0) {
            transaction.setDetails(detailsList.item(0).getTextContent());
        }

        NodeList amountList = transactionElement.getElementsByTagName("Amount");
        if (amountList.getLength() > 0) {
            transaction.setAmount(new BigDecimal(amountList.item(0).getTextContent()));
        }

        return transaction;
    }

}
