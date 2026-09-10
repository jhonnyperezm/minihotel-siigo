# MiniHotel → Siigo Integration

Aplicación Spring Boot que sincroniza automáticamente las reservaciones de salida de **MiniHotel** como facturas de venta en **Siigo Nube**, vía [Siigo API](https://siigoapi.docs.apiary.io/). Soporta múltiples hoteles (multi-tenant), reintento de sincronizaciones fallidas y auditoría completa en base de datos local.

Es la adaptación a Siigo del proyecto original `minihotel-wo-json-integration`. Todo lo relativo a World Office fue eliminado.

---

## Contenido

- [Qué cambia frente a World Office](#que-cambia-frente-a-world-office)
- [Arquitectura](#arquitectura)
- [Flujo de sincronización](#flujo-de-sincronizacion)
- [Webhooks en tiempo real](#webhooks-en-tiempo-real)
- [Requisitos](#requisitos)
- [Configuración](#configuracion)
- [Puesta a punto inicial](#puesta-a-punto-inicial)
- [Ejecución local](#ejecucion-local)
- [Endpoints REST](#endpoints-rest)
- [Panel de administración](#panel-de-administracion)
- [Pruebas](#pruebas)
- [Reglas de negocio](#reglas-de-negocio)
- [Errores frecuentes](#errores-frecuentes)

---

## Que cambia frente a World Office

Estas son las diferencias que obligaron a rediseñar partes del proyecto, no solo a renombrar clases.

| Tema | World Office | Siigo |
|------|--------------|-------|
| **Autenticación** | Token estático por licencia, header `Authorization: WO <token>` | OAuth: `POST /auth` con `username` + `access_key` devuelve un JWT de 24 h. Header `Authorization: Bearer <token>` y **`Partner-Id` obligatorio** |
| **Multi-empresa** | Un token cubre todas las empresas; cada hotel se resuelve con `empresaId` | **Cada empresa es un tenant independiente con sus propias credenciales**. Token y catálogos se cachean por tenant |
| **Numeración** | `prefijoId` obtenido de `listarPrefijoDocumento` | `document.id`, obtenido de `/v1/document-types?type=FV` |
| **Tercero en la factura** | Se referencia por `idTerceroExterno` (id interno) | Se referencia por **identificación** + `branch_office` |
| **Creación de terceros** | Requiere resolver catálogos de tipo de contribuyente, responsabilidad fiscal, tipo de identificación y ciudad por id | Códigos fijos: `id_type` (13 CC, 31 NIT, 41 PA…), `fiscal_responsibilities: ["R-99-PN"]` y ciudad en **códigos DANE** |
| **Producto del renglón** | `idInventario` (id numérico) | `code` (código del producto) |
| **Impuestos** | El valor se dividía entre 1,19 a mano | El IVA viaja explícito en `items[].taxes[]`; el precio va **sin** impuesto y el porcentaje sale del catálogo `/v1/taxes` |
| **Medios de pago** | `idFormaPago` | `payments[]`, y **la suma debe igualar el total de la factura** |
| **Contabilización** | Paso aparte: `contabilizarDocumento` | **No existe**: Siigo contabiliza al crear |
| **Facturación electrónica** | Endpoint `facturaElectronica/{id}` tras contabilizar | `stamp.send: true` al crear, o `POST /v1/invoices/{id}/stamp` después |
| **ID del documento** | Numérico | **GUID** (por eso `siigoId` es `String` en la auditoría) |
| **Ciudades** | Catálogo remoto `listarCiudades` | No hay endpoint; la tabla de códigos DANE vive en `CiudadSiigo` |

---

## Arquitectura

```
ScheduledSyncService  (cron 00:10 diario)
        │
        ▼
IntegrationService
  ├── MiniHotelClient          → API MiniHotel (XML sobre HTTP, parseado con XmlResponseParser)
  ├── ClienteService           → Upsert de terceros en Siigo (/v1/customers)
  ├── ReservationInvoiceMapper → Reservación → CrearFacturaRequest
  ├── SiigoFacturaClient       → POST /v1/invoices (+ stamp a la DIAN)
  └── SyncTransactionService   → Auditoría en H2 (PENDING → SUCCESS / FAILED)

SiigoTokenStore       → token JWT por tenant, renovación automática
SiigoCatalogoService  → catálogos por tenant (comprobantes, pagos, impuestos, vendedores, bodegas)
```

### Tecnologías

| Componente | Tecnología |
|------------|------------|
| Framework | Spring Boot 3.3.6 / Java 17 |
| HTTP cliente | RestTemplate (bloqueante) |
| Base de datos | H2 (archivo local `./data/sync_transactions`) |
| Seguridad de la app | HTTP Basic Auth (configurable por env) |
| Formato MiniHotel | XML (parseado internamente) |
| Formato Siigo | JSON REST |

---

## Flujo de sincronizacion

1. `ScheduledSyncService` corre todos los días a las **00:10** para cada hotel activo.
2. Consulta a MiniHotel las reservaciones con fecha de salida del día anterior.
3. Para cada reservación:
   - Descarta las excluidas (Airbnb, Expedia, marcadas como "Efectivo" en el `zip`).
   - Registra una transacción en estado `PENDING`.
   - Obtiene el balance de cargos.
   - Busca o crea el tercero en Siigo.
   - Construye la factura (renglones, impuestos, medio de pago).
   - Envía `POST /v1/invoices`.
   - Si fue exitoso: guarda GUID, número, fecha, estado DIAN y CUFE → `SUCCESS`.
   - Si falló: guarda el mensaje de error y el payload enviado → `FAILED`.
4. Al terminar, reintenta las transacciones en estado `FAILED`.
5. A las **23:10** un segundo proceso timbra ante la DIAN las facturas del día que quedaron en `Draft`.

---

## Webhooks en tiempo real

Además del cron, la aplicación recibe notificaciones de MiniHotel y factura en el momento del
check-out. Detalle completo en **[WEBHOOKS.md](WEBHOOKS.md)**.

```
POST /api/webhooks/minihotel   →  guarda el evento  →  200 OK
                                       │
                                       └─ (asíncrono) syncFacturasASiigo(reservationNumber)
```

Tres cosas que conviene tener claras:

- **El webhook es un disparador, no una fuente de datos.** El payload no trae los consumos, así
  que se sigue consultando `GetReservationBalance()`. Por eso el núcleo no cambió.
- **Nunca se factura dentro del handler.** MiniHotel da 15 segundos y el timbrado ante la DIAN
  puede tardar más. Se responde en cuanto el evento queda guardado.
- **El cron no se quita.** MiniHotel abandona tras seis reintentos (el último a las 6 h); el
  cron pasa a ser la red de reconciliación. Las restricciones únicas evitan facturas dobles.

El disparador por defecto es `room.occupancy.updated` con `occupied: false`, que es lo que
MiniHotel recomienda por encima del estado de la reservación. Se cambia con
`integrations.webhooks.disparador`.

Para arrancar sin riesgo, despliega con `facturacion-automatica: false`, observa unos días el
panel de `/webhooks.html` y actívalo después hotel por hotel.

---

## Requisitos

- Java 17+
- Maven 3.8+
- Acceso de red a la API de MiniHotel y a `https://api.siigo.com`
- Credenciales de Siigo API y un **Partner-Id** de una aplicación registrada

### Cómo obtener las credenciales

Siigo Nube → menú izquierdo **Alianzas** → botón **"Mi Credencial API"**. Allí se genera el `username`, el `access_key` y el `Partner-Id`. Desde el 31 de mayo de 2025 Siigo solo acepta Partner-Id asociados a aplicaciones registradas.

Para pruebas, se pueden solicitar credenciales de sandbox a soporte de Siigo indicando el NIT registrado.

---

## Configuracion

### Variables de entorno

| Variable | Descripción | Valor por defecto |
|----------|-------------|-------------------|
| `MINIHOTEL_BASE_URL` | URL base de la API MiniHotel | `https://api.minihotel.cloud/` |
| `SIIGO_BASE_URL` | URL base de Siigo API | `https://api.siigo.com` |
| `SIIGO_PARTNER_ID` | Partner-Id de la aplicación registrada | — (obligatorio en prod) |
| `SIIGO_USERNAME` / `SIIGO_ACCESS_KEY` | Credenciales por defecto | — |
| `SIIGO_<HOTEL>_USER` / `SIIGO_<HOTEL>_KEY` | Credenciales por hotel | — |
| `WEBHOOK_USERNAME` / `WEBHOOK_PASSWORD` | Basic Auth que usará MiniHotel | — |
| `WEBHOOK_DISPARADOR` | `OCUPACION`, `RESERVA_OUT`, `AMBOS` o `NINGUNO` | `OCUPACION` |
| `WEBHOOK_FACTURACION_AUTOMATICA` | Interruptor de emergencia | `true` |
| `API_USERNAME` / `API_PASSWORD` | HTTP Basic de los endpoints de esta app | `admin` / `123456` |

### Configuración multi-tenant

En Siigo cada empresa se autentica por separado. Cada hotel lleva su propio bloque `siigo-auth`:

```yaml
integrations:
  hotels:
    - hotel-key: "hotel-Provenza"
      hotel-name: "Autenthico Provenza Hotel Medellin"
      enabled: true
      minihotel-auth:
        username: "${HOTEL_PROVENZA_USER}"
        password: "${HOTEL_PROVENZA_PASS}"
        hotel-id: "${HOTEL_PROVENZA_ID}"
        siigo-tenant: "provenza"        # clave que se guarda en la auditoría
      siigo-auth:
        username: "${SIIGO_PROVENZA_USER}"
        access-key: "${SIIGO_PROVENZA_KEY}"
        partner-id: "${SIIGO_PARTNER_ID}"
      facturacion:
        tipo-comprobante-id: 22
        vendedor-id: 629
        centro-costo-id: 235
        ciudad-por-defecto: "Medellín"
        enviar-dian: true
        usar-fecha-actual: true
```

> **Si varios hoteles facturan bajo la misma empresa de Siigo**, repite las mismas credenciales y sepáralos con `centro-costo-id` distinto (o con comprobantes distintos). Usa el mismo `siigo-tenant` para que compartan token y caché de catálogos.

### Parámetros de facturación

Todos admiten id explícito o búsqueda por nombre/código:

| Parámetro | Para qué sirve |
|-----------|----------------|
| `tipo-comprobante-codigo` / `tipo-comprobante-id` | Comprobante FV con el que se numera |
| `vendedor-identificacion` / `vendedor-id` | Usuario vendedor (`seller`) |
| `centro-costo-id` | Separa la operación de cada hotel |
| `bodega-id` | Solo se envía si el producto maneja inventario |
| `producto-nacional` | Código del servicio gravado con IVA |
| `producto-extranjero` | Código del servicio exento (huéspedes del exterior) |
| `impuesto-iva` / `impuesto-iva-id` | IVA a aplicar; el porcentaje sale del catálogo |
| `forma-pago-contado` / `forma-pago-credito` | Nombres de los medios de pago en Siigo |
| `consumidor-final-identificacion` | Tercero genérico para reservas sin documento |
| `ciudad-por-defecto` | Ciudad cuando MiniHotel no informa una reconocible |
| `enviar-dian` | `stamp.send` al crear la factura |
| `enviar-email` | `mail.send` al crear la factura |
| `usar-fecha-actual` | Emite con la fecha de hoy en vez de la de salida |

---

## Puesta a punto inicial

Los ids de Siigo son propios de cada empresa. En vez de adivinarlos, la app los descubre:

```bash
# Resumen de lo que la integración resolvió para un hotel
curl -u admin:123456 http://localhost:8082/api/catalogo/hotel-Provenza/resumen

# Catálogos individuales
curl -u admin:123456 http://localhost:8082/api/catalogo/hotel-Provenza/comprobantes
curl -u admin:123456 http://localhost:8082/api/catalogo/hotel-Provenza/formas-pago
curl -u admin:123456 http://localhost:8082/api/catalogo/hotel-Provenza/impuestos
curl -u admin:123456 http://localhost:8082/api/catalogo/hotel-Provenza/usuarios
curl -u admin:123456 http://localhost:8082/api/catalogo/hotel-Provenza/centros-costo

# Verificar que un código de producto exista antes de configurarlo
curl -u admin:123456 http://localhost:8082/api/catalogo/hotel-Provenza/productos/ALOJAMIENTO
```

**Antes de facturar, en Siigo Nube deben existir:**

1. Un comprobante de tipo **FV** (electrónico si vas a timbrar).
2. Los productos/servicios `producto-nacional` y `producto-extranjero`.
3. El impuesto de IVA.
4. Los medios de pago de contado y crédito.
5. Al menos un usuario vendedor.
6. El tercero **consumidor final** con la identificación configurada.

---

## Ejecucion local

```bash
mvn spring-boot:run                # arranca en http://localhost:8082
mvn clean install -DskipTests      # compila sin pruebas
```

### Consola H2

```
URL:  http://localhost:8082/h2-console
JDBC: jdbc:h2:file:./data/sync_transactions
User: sa    Pass: (vacío)
```

---

## Endpoints REST

Todos requieren **HTTP Basic** con `API_USERNAME` / `API_PASSWORD`.

### Sincronización manual

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `GET` | `/api/sync/hotel/{hotelKey}?date=yyyy-MM-dd` | Sincroniza un hotel para una fecha |
| `GET` | `/api/sync/hotel/{hotelKey}/range?fromDate=...&toDate=...` | Rango de fechas |
| `GET` | `/api/sync/hotel/{hotelKey}/reservation/{reservationNumber}` | Una reservación puntual |
| `GET` | `/api/sync/all-hotels?date=yyyy-MM-dd` | Todos los hoteles activos |
| `GET` | `/api/sync/hotel/{hotelKey}/reservation-ids?fromDate=...&toDate=...` | Solo los IDs, sin facturar |

### Gestión de hoteles

| Método | Endpoint |
|--------|----------|
| `GET` | `/api/sync/hotels` |
| `GET` | `/api/sync/hotels/active` |
| `GET` | `/api/sync/hotels/{hotelKey}` |

### Catálogos de Siigo

| Método | Endpoint |
|--------|----------|
| `GET` | `/api/catalogo/{hotelKey}/resumen` |
| `GET` | `/api/catalogo/{hotelKey}/comprobantes` |
| `GET` | `/api/catalogo/{hotelKey}/formas-pago` |
| `GET` | `/api/catalogo/{hotelKey}/impuestos` |
| `GET` | `/api/catalogo/{hotelKey}/usuarios` |
| `GET` | `/api/catalogo/{hotelKey}/centros-costo` |
| `GET` | `/api/catalogo/{hotelKey}/bodegas` |
| `GET` | `/api/catalogo/{hotelKey}/productos/{codigo}` |
| `GET` | `/api/catalogo/{hotelKey}/ciudades?nombre=...` |
| `POST` | `/api/catalogo/{hotelKey}/refrescar` |

### Auditoría

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `GET` | `/api/sync-transactions` | Todas las transacciones |
| `GET` | `/api/sync-transactions/{id}` | Por ID |
| `GET` | `/api/sync-transactions/reservation/{reservationNumber}` | Por número de reservación |
| `GET` | `/api/sync-transactions/search?startDate=...&endDate=...&status=...` | Filtrada |
| `POST` | `/api/sync-transactions/{id}/retry` | Reintenta una transacción |
| `GET` | `/api/sync-transactions/{id}/errores-dian` | Detalle del rechazo de la DIAN |
| `POST` | `/api/sync-transactions/{id}/enviar-dian` | Reenvía la factura a la DIAN |

**Reintento con número de reservación diferente:**

```json
POST /api/sync-transactions/42/retry
{ "customReservationNumber": "RES-9999" }
```

Si el body está vacío o el número coincide con el original, se reutiliza la misma transacción. Si se indica otro, se crea una nueva.

### Consultas a MiniHotel (diagnóstico)

| Método | Endpoint |
|--------|----------|
| `GET` | `/api/reservations/by-email?email=...` |
| `GET` | `/api/reservations/by-passport?passportNumber=...` |
| `GET` | `/api/reservations/by-minihotel-id?minihotelId=...` |
| `GET` | `/api/reservations/by-date-range-out?fromDate=...&toDate=...` |
| `GET` | `/api/reservations/balance/{reservationId}` |

### Webhooks

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `POST` | `/api/webhooks/minihotel` | Recepción de notificaciones (credencial de MiniHotel) |
| `GET` | `/api/webhooks/minihotel/ping` | Prueba de conectividad y configuración vigente |
| `GET` | `/api/webhooks/minihotel/events` | Eventos recibidos, con filtros |
| `GET` | `/api/webhooks/minihotel/events/{id}` | Detalle con el payload completo |
| `POST` | `/api/webhooks/minihotel/events/{id}/reprocesar` | Reintento manual |
| `POST` | `/api/webhooks/minihotel/events/limpiar` | Purga de eventos vencidos |

---

## Panel de administracion

Interfaz web en `http://localhost:8082/`:

- Tabla de transacciones con paginación (10 / 25 / 50 / 100).
- Filtros por estado, hotel, número de reservación y rango de fechas.
- Botón de reintento para las fallidas, con opción de cambiar el número de reservación.
- Visualización del error y del payload JSON enviado a Siigo.

En `/webhooks.html` hay una segunda vista para los eventos recibidos por webhook: estado del
módulo, contadores, filtros y reproceso manual con el JSON crudo a la vista.

---

## Pruebas

```bash
mvn test
mvn test -Dtest=ReservationInvoiceMapperTest
```

Cobertura actual: `Util`, `CiudadSiigo`, `ReservationInvoiceMapper`, `ClienteService`,
`HotelContextService`, `SyncTransactionService`, `WebhookDisparadorService` y
`WebhookEventService`. Usan H2 en memoria y Mockito. Los tests de webhooks parten de los
JSON de ejemplo de la documentación de MiniHotel.

---

## Reglas de negocio

| Regla | Descripción |
|-------|-------------|
| **Transacciones CASH** | Las líneas con `department = "CASH"` son pagos, no cargos: no se facturan. Su presencia determina el medio de pago (CASH → contado, sin CASH → crédito). |
| **Resumen de reservación** | Si hay varios cargos y alguno tiene hora `00:00`, ese es el resumen final: se factura solo ese, para no duplicar el consumo. |
| **Huésped extranjero** | País distinto de Colombia → se usa `producto-extranjero` **sin IVA**. Nacional → `producto-nacional` con IVA. |
| **Desagregación del IVA** | MiniHotel entrega el valor con IVA incluido. El renglón se envía con el precio base (`valor / 1,19`) más el impuesto explícito, y el medio de pago recompone el total. |
| **Medio de pago** | Si la reserva trae valor en `zip`, ese es el nombre del medio de pago en Siigo. Si no, aplica la regla de CASH. |
| **Tercero por defecto** | Reserva sin identificación → se factura al consumidor final configurado. |
| **Tipo de documento** | Se infiere: formato `NNNNNNNNN-D` → NIT; con letras → pasaporte; extranjero con solo dígitos → cédula de extranjería; resto → cédula. Si no cumple el formato numérico de Siigo, se degrada a documento extranjero (42). |
| **Unicidad de reservación** | No se puede sincronizar dos veces la misma reservación para el mismo hotel. Para re-sincronizar, usar el endpoint de reintento. |
| **Fecha del documento** | Por defecto la de salida. La DIAN rechaza facturas electrónicas con fecha anterior a hoy, por eso en producción se recomienda `usar-fecha-actual: true`. |
| **Contexto multi-tenant** | El hotel activo vive en un `ThreadLocal` y siempre se limpia en un bloque `finally`. |

---

## Errores frecuentes

| Síntoma | Causa probable |
|---------|----------------|
| `401 Unauthorized` al arrancar | `username` / `access_key` incorrectos, o falta el `Partner-Id` |
| `parameter_required` en la factura | Falta un campo obligatorio; el mensaje indica cuál |
| `not_found` al crear la factura | El comprobante, producto, medio de pago o tercero no existe en esa empresa |
| `stock_control` | Se envió bodega para un producto sin control de inventarios: quita `bodega-id` |
| `too_many_requests` | Se superaron las 100 peticiones/minuto de producción |
| La DIAN rechaza la factura | Consulta `GET /api/sync-transactions/{id}/errores-dian` |
| El total no cuadra | Revisa que el IVA configurado coincida con el del producto en Siigo |

---

## Convención de commits

`Fix: <descripción en español>` (p. ej. `Fix: Ajuste en desagregación de IVA para huéspedes extranjeros`)
# minihotel-siigo
