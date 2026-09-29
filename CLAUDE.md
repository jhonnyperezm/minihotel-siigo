# CLAUDE.md

Guía para trabajar con este repositorio.

## Build & Run

```bash
mvn spring-boot:run                 # arranca en :8082 (perfil dev)
mvn clean install -DskipTests
mvn test
mvn test -Dtest=ClassName

# Consola H2
# http://localhost:8082/h2-console  ·  JDBC: jdbc:h2:file:./data/sync_transactions
```

## Arquitectura

Spring Boot 3.3.6 monomódulo (Java 17). Todo el intercambio con Siigo es JSON sobre REST.
MiniHotel es la excepción: expone XML, que `XmlResponseParser` convierte a los DTOs de `dto/minihotel`.

### Flujo principal

**Sync → MiniHotelClient (XML) → parse → IntegrationService → SiigoFacturaClient (JSON) → auditoría H2**

1. `ScheduledSyncService` corre cada media hora (salidas de ayer y hoy) y a las 00:10
   (reconciliación y reintento de fallidas) para todos los hoteles activos.
2. `IntegrationService.syncFacturasASiigo()` obtiene las reservaciones y dirige el proceso.
3. `ClienteService` hace upsert del huésped en `/v1/customers` antes de facturar.
4. `ReservationInvoiceMapper` arma el `CrearFacturaRequest`.
5. `SiigoFacturaClient` crea la factura; si `enviar-dian` está activo, se timbra en la misma llamada.
6. `SyncTransactionService` persiste el resultado (SUCCESS / FAILED) en H2.

### Multi-tenant: el punto crítico

En World Office un token cubría toda la licencia. **En Siigo cada empresa es un tenant con sus
propias credenciales**, así que el token (`SiigoTokenStore`) y los catálogos
(`SiigoCatalogoService`) se cachean por tenant.

`HotelContextService` mantiene el hotel activo en un `ThreadLocal` y resuelve, a partir de él,
las credenciales y los parámetros de facturación, con respaldo en la configuración global.

**Patrón obligatorio — siempre try-finally:**

```java
try {
    hotelContextService.setCurrentHotel(hotelKey);
    integrationService.syncFacturasASiigo(date, date);
} finally {
    hotelContextService.clearCurrentHotel();
}
```

Si olvidas el `finally`, el hilo queda apuntando al tenant equivocado y la siguiente petición
factura en la empresa incorrecta.

### Distribución de paquetes (`co.com.siigo.integrations.*`)

| Paquete | Contenido |
|---------|-----------|
| `client/` | `MiniHotelClient` (XML POST), `SiigoAuthClient`, `SiigoFacturaClient`, `SiigoClienteClient`, `SiigoCatalogoClient`, `SiigoProductoClient`, `SiigoErrores` |
| `dto/webhook/` | `WebhookEnvelope`, `ReservationEventPayload`, `RoomOccupancyPayload` y sus enums |
| `config/` | `IntegrationProperties`, `RestClientsConfig`, `SecurityConfig` |
| `service/` | `IntegrationService`, `ScheduledSyncService`, `ClienteService`, `SiigoCatalogoService`, `HotelContextService`, `SyncTransactionService`, `ReservationBalanceService`, `WebhookEventService`, `WebhookDisparadorService`, `WebhookProcessingService` |
| `mapper/` | `ReservationInvoiceMapper` — MiniHotel → factura de Siigo |
| `web/` | `SyncController`, `SyncTransactionController`, `CatalogoController`, `BalanceController`, `ReservationController`, `DocumentController`, `IntegrationController`, `GlobalExceptionHandler` |
| `entity/` | `SyncTransaction`, `WebhookEvent` (entidades JPA en H2) |
| `dto/minihotel/` | DTOs de respuesta de MiniHotel |
| `dto/siigo/` | DTOs de petición/respuesta de Siigo y sus enums |
| `security/` | `SiigoTokenStore` (JWT por tenant), `UnauthorizedException` |
| `util/` | `RetryExecutor`, `Util`, `XmlResponseParser`, `CiudadSiigo` |

### Autenticación con Siigo

`POST /auth` con `{username, access_key}` devuelve un JWT de 24 h. `SiigoTokenStore` lo cachea
por tenant y lo renova con el margen de `token-refresh-margin-seconds` (300 s). Todas las
llamadas llevan `Authorization: Bearer <token>` y `Partner-Id: <app>`.

Ante un 401, los clientes invalidan el token del tenant para forzar reautenticación.

### Caché de catálogos

`SiigoCatalogoService` guarda por tenant: tipos de comprobante, medios de pago, impuestos,
usuarios, centros de costo, bodegas y productos. Carga perezosa por defecto; con
`precargar-catalogos: true` se precargan al arrancar (recomendado en producción, para que un
error de credenciales salte en el arranque y no en la primera factura).

### Webhooks: la regla que no se puede romper

El handler de `/api/webhooks/minihotel` **nunca** debe facturar de forma síncrona. MiniHotel
da 15 segundos y considera fallida cualquier entrega que exceda ese plazo; el timbrado ante la
DIAN puede tardar más. El handler solo deserializa, deduplica, guarda y encola; el trabajo real
vive en `WebhookProcessingService` sobre el pool `webhookExecutor`.

Si alguna vez hay que agregar lógica al handler, la pregunta es: ¿esto puede tardar? Si la
respuesta no es un no rotundo, va en el procesador asíncrono.

La deduplicación es por `hotelCode + notificationID`, no por `eventId`: la documentación de
MiniHotel describe el GUID como único por evento, pero sus propios ejemplos lo repiten entre
notificaciones distintas.

El disparador vive aislado en `WebhookDisparadorService` para que la regla de negocio sea fácil
de leer y de cambiar. Si hay que ajustar qué evento factura, ese es el único archivo a tocar.

## Reglas de negocio a respetar

- Las transacciones con `department = "CASH"` son pagos: no generan renglón.
- El medio de pago lo decide el `zip` de la reserva: vacío o nulo → `forma-pago-contado`
  (Efectivo, código 1); con cualquier valor → `forma-pago-credito` (Pagos, código 6).
- Si hay varios cargos y alguno tiene hora `00:00`, ese es el resumen: se factura solo ese.
- Huésped del exterior → producto exento, sin IVA. Nacional → producto gravado.
- MiniHotel entrega valores **con IVA incluido**: hay que desagregarlos antes de enviarlos,
  porque Siigo espera `items[].price` sin impuesto.
- La suma de `payments[].value` debe igualar el total de la factura; por eso el mapper
  recompone el total línea a línea en vez de sumar los montos originales.
- No existe paso de contabilización: no lo reintroduzcas.
- El cron nocturno se conserva aunque el webhook esté activo: es la reconciliación de lo que
  MiniHotel abandonó tras sus seis reintentos. No lo elimines por considerarlo redundante.
- Una reservación cancelada con factura ya emitida se corrige con nota crédito, no anulando.
  El webhook solo la marca para revisión manual.

## Convención de commits

`Fix: <descripción en español>` (p. ej. `Fix: Ajuste para guardar activos fijos`)
