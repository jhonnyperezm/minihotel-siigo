# Configuración Multi-Tenant

Este sistema sincroniza múltiples hoteles de MiniHotel, cada uno con su propia autenticación y
su propia empresa en Siigo Nube.

## Por qué cambió respecto a World Office

En World Office bastaba **un token** para toda la licencia: los hoteles se separaban indicando
`wo-empresa-id` y `wo-prefijo-id` en cada petición.

En Siigo **no existe ese concepto**. Cada empresa de Siigo Nube:

- tiene sus propias credenciales (`username` + `access_key`),
- emite su propio token JWT,
- tiene sus propios ids de comprobante, impuestos, productos, medios de pago y vendedores.

Por eso la integración cachea **token y catálogos por tenant**, y cada hotel declara su bloque
`siigo-auth`.

---

## Escenario A — cada hotel es una empresa distinta en Siigo

Es el caso más común cuando cada hotel factura con su propio NIT.

```yaml
integrations:
  hotels:
    - hotel-key: "hotel-Sajonia"
      hotel-name: "Autenthico Sajonia Hotel Aeropuerto"
      enabled: true
      minihotel-auth:
        username: "${HOTEL_SAJONIA_USER}"
        password: "${HOTEL_SAJONIA_PASS}"
        hotel-id: "${HOTEL_SAJONIA_ID}"
        siigo-tenant: "sajonia"
      siigo-auth:
        username: "${SIIGO_SAJONIA_USER}"
        access-key: "${SIIGO_SAJONIA_KEY}"
        partner-id: "${SIIGO_PARTNER_ID}"
      facturacion:
        tipo-comprobante-id: 22
        vendedor-id: 629
        producto-nacional: "ALOJAMIENTO"
        producto-extranjero: "ALOJAMIENTO-EXENTO"
        ciudad-por-defecto: "Medellín"
        enviar-dian: true
        usar-fecha-actual: true

    - hotel-key: "hotel-Provenza"
      hotel-name: "Autenthico Provenza Hotel Medellin"
      enabled: true
      minihotel-auth:
        username: "${HOTEL_PROVENZA_USER}"
        password: "${HOTEL_PROVENZA_PASS}"
        hotel-id: "${HOTEL_PROVENZA_ID}"
        siigo-tenant: "provenza"
      siigo-auth:
        username: "${SIIGO_PROVENZA_USER}"
        access-key: "${SIIGO_PROVENZA_KEY}"
        partner-id: "${SIIGO_PARTNER_ID}"
      facturacion:
        tipo-comprobante-id: 31
        vendedor-id: 640
        ciudad-por-defecto: "Medellín"
        enviar-dian: true
        usar-fecha-actual: true
```

Cada hotel tendrá su propio token y su propio caché de catálogos.

---

## Escenario B — varios hoteles bajo la misma empresa de Siigo

Cuando todos facturan con el mismo NIT, se separan por **centro de costos**.

```yaml
integrations:
  siigo:
    auth:
      username: "${SIIGO_USERNAME}"
      access-key: "${SIIGO_ACCESS_KEY}"
      partner-id: "${SIIGO_PARTNER_ID}"
    facturacion:
      tipo-comprobante-id: 22
      vendedor-id: 629
      producto-nacional: "ALOJAMIENTO"
      producto-extranjero: "ALOJAMIENTO-EXENTO"
      enviar-dian: true
      usar-fecha-actual: true

  hotels:
    - hotel-key: "hotel-Provenza"
      hotel-name: "Autenthico Provenza"
      enabled: true
      minihotel-auth:
        username: "${HOTEL_PROVENZA_USER}"
        password: "${HOTEL_PROVENZA_PASS}"
        hotel-id: "${HOTEL_PROVENZA_ID}"
        siigo-tenant: "autenthico"     # mismo tenant: comparten token y catálogos
      facturacion:
        centro-costo-id: 235

    - hotel-key: "hotel-laureles"
      hotel-name: "Autenthico Laureles"
      enabled: true
      minihotel-auth:
        username: "${HOTEL_LAURELES_USER}"
        password: "${HOTEL_LAURELES_PASS}"
        hotel-id: "${HOTEL_LAURELES_ID}"
        siigo-tenant: "autenthico"
      facturacion:
        centro-costo-id: 236
```

Al omitir `siigo-auth` y compartir el mismo `siigo-tenant`, ambos hoteles usan las credenciales
globales y **un solo token**, lo que reduce las llamadas a `/auth`.

---

## Descubrir los valores de configuración

Los ids son propios de cada empresa. No los inventes: consúltalos.

```bash
BASE=http://localhost:8082
AUTH="-u admin:123456"

# Qué resolvió la integración para este hotel
curl $AUTH $BASE/api/catalogo/hotel-Provenza/resumen

# Catálogos crudos
curl $AUTH $BASE/api/catalogo/hotel-Provenza/comprobantes    # -> tipo-comprobante-id
curl $AUTH $BASE/api/catalogo/hotel-Provenza/usuarios        # -> vendedor-id
curl $AUTH $BASE/api/catalogo/hotel-Provenza/impuestos       # -> impuesto-iva-id
curl $AUTH $BASE/api/catalogo/hotel-Provenza/formas-pago     # -> forma-pago-*-id
curl $AUTH $BASE/api/catalogo/hotel-Provenza/centros-costo   # -> centro-costo-id
curl $AUTH $BASE/api/catalogo/hotel-Provenza/bodegas         # -> bodega-id

# Validar que un producto exista antes de configurarlo
curl $AUTH $BASE/api/catalogo/hotel-Provenza/productos/ALOJAMIENTO
```

Tras cambiar algo en Siigo Nube:

```bash
curl -X POST $AUTH $BASE/api/catalogo/hotel-Provenza/refrescar
```

---

## Cómo se usa el contexto en el código

El hotel activo vive en un `ThreadLocal`. De él salen las credenciales, el tenant y los
parámetros de facturación.

```java
try {
    hotelContextService.setCurrentHotel("hotel-Provenza");

    hotelContextService.getTenantKey();          // "provenza"
    hotelContextService.getCredencialesSiigo();  // username/access-key/partner-id
    hotelContextService.getFacturacion();        // parámetros del hotel, o los globales

    integrationService.syncFacturasASiigo(date, date);
} finally {
    hotelContextService.clearCurrentHotel();     // imprescindible
}
```

**El `finally` no es opcional.** Sin él, el hilo conserva el tenant anterior y la siguiente
petición facturaría en la empresa equivocada.

---

## Sincronización manual

```bash
# Un hotel, una fecha
curl $AUTH "$BASE/api/sync/hotel/hotel-Provenza?date=2026-08-25"

# Un hotel, un rango
curl $AUTH "$BASE/api/sync/hotel/hotel-Provenza/range?fromDate=2026-08-20&toDate=2026-08-25"

# Una reservación puntual
curl $AUTH "$BASE/api/sync/hotel/hotel-Provenza/reservation/RES-12345"

# Todos los hoteles activos
curl $AUTH "$BASE/api/sync/all-hotels?date=2026-08-25"
```

---

## Auditoría

La tabla `sync_transactions` guarda, por cada intento:

| Columna | Contenido |
|---------|-----------|
| `reservation_number` | Número de reservación en MiniHotel |
| `minihotel_hotel_id` | Hotel en MiniHotel |
| `siigo_tenant` | Tenant de Siigo con el que se facturó (permite reintentar) |
| `siigo_id` | **GUID** de la factura en Siigo |
| `siigo_numero` | Nombre visible del documento (p. ej. `FV-2-22`) |
| `siigo_fecha` | Fecha del documento |
| `estado_dian` | `Draft`, `Accepted` o `Rejected` |
| `cufe` | Código único de facturación electrónica |
| `status` | `PENDING`, `SUCCESS` o `FAILED` |
| `error_message` | Mensaje de error de Siigo, ya traducido |
| `request_payload` | JSON exacto que se envió |

Restricción de unicidad: `(reservation_number, minihotel_hotel_id)`. Para re-sincronizar una
reservación existente hay que usar el endpoint de reintento.

---

## Deshabilitar un hotel temporalmente

```yaml
- hotel-key: "hotel-stay-envigado"
  enabled: false
```

Queda fuera de la sincronización automática y de `/api/sync/all-hotels`, pero sigue siendo
consultable de forma individual.

---

## Problemas comunes

| Problema | Causa | Solución |
|----------|-------|----------|
| `401` al sincronizar un hotel | Credenciales o Partner-Id del tenant incorrectos | Verifica `siigo-auth` de ese hotel |
| `not_found` al crear la factura | Comprobante, producto o medio de pago inexistente en esa empresa | Consulta `/api/catalogo/{hotelKey}/resumen` |
| Facturas emitidas en la empresa equivocada | Falta el `clearCurrentHotel()` en un `finally` | Revisa el flujo que lo omitió |
| Cambios en Siigo Nube que no se reflejan | Catálogos en caché | `POST /api/catalogo/{hotelKey}/refrescar` |
| Reintento falla con "Hotel no encontrado para el tenant" | El `siigo-tenant` cambió tras crear la transacción | Alinea el valor con el guardado en la auditoría |
