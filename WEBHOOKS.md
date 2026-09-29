# Webhooks de MiniHotel

Recepción de notificaciones en tiempo real desde MiniHotel para facturar en Siigo en el
momento del check-out, en lugar de esperar al cron nocturno.

---

## Idea central

El webhook es un **disparador**, no una fuente de datos. El payload de MiniHotel trae huésped,
habitaciones y fechas, pero **no trae los consumos**. Por eso el flujo sigue consultando
`GetReservationBalance()` como siempre; lo único que cambia es *cuándo* se dispara.

Eso hace que el cambio en el núcleo sea mínimo: el procesador termina llamando al mismo
`integrationService.syncFacturasASiigo(reservationNumber)` que ya usaba el cron.

```
POST /api/webhooks/minihotel   (Basic Auth)
   │
   ├─ deserializa el sobre
   ├─ deduplica por hotelCode + notificationID
   ├─ guarda el evento en webhook_events
   ├─ evalúa si dispara factura
   └─ responde 200 OK                        ← en milisegundos
          │
          └─ (asíncrono, pool webhook-*)
               resuelve el hotel por hotelCode
               → syncFacturasASiigo(reservationNumber)
               → actualiza el evento a PROCESADO / FALLIDO
```

---

## Por qué es asíncrono

MiniHotel da **15 segundos** y marca como fallida cualquier entrega que exceda ese plazo.
Facturar implica consultar el balance, autenticar en Siigo, resolver el tercero, emitir la
factura y timbrarla ante la DIAN. El timbrado por sí solo puede superar ese tiempo.

Si se facturara dentro del handler, una DIAN lenta produciría *timeouts*, MiniHotel
reintentaría, y cada reintento dispararía otra factura. El diseño evita esa cadena por
completo: se responde en cuanto el evento está guardado.

---

## Idempotencia

MiniHotel reintenta seis veces una entrega fallida: a los 10 s, 1 min, 5 min, 10 min, 1 h y
6 h. Hay tres barreras:

1. **Restricción única `(hotel_code, notification_id)`** en `webhook_events`. Un reintento se
   detecta y se responde 200 sin volver a facturar.
2. **Restricción única `(reservation_number, minihotel_hotel_id)`** en `sync_transactions`,
   que ya existía. Aunque dos eventos distintos apuntaran a la misma reserva, no se emite
   una segunda factura.
3. **Deduplicación por reservación** en reservas de varias habitaciones, donde llega un evento
   por habitación.

> **Detalle de la documentación de MiniHotel:** describe `eventId` como un GUID único por
> evento, pero en sus propios ejemplos las notificaciones *reservation.updated* y
> *reservation.cancelled* comparten el GUID `597828f9-5870-473c-afe6-022771d72b66` con
> distinto `notificationID`. Por eso la llave de deduplicación es
> **`hotelCode` + `notificationID`**, no el GUID. Vale la pena confirmarlo con MiniHotel.

---

## Qué evento dispara la factura

Se configura con `integrations.webhooks.disparador`:

| Valor | Evento | Cuándo usarlo |
|-------|--------|---------------|
| `OCUPACION` *(por defecto)* | `room.occupancy.updated` con `occupied: false` | Recomendado. MiniHotel advierte que el estado de la reservación no siempre refleja el estado real de la habitación |
| `RESERVA_OUT` | `reservation.updated` con estado `OUT` | Si prefieres atarte al estado de la reservación |
| `AMBOS` | Cualquiera de los dos | El primero que llegue dispara; las restricciones únicas evitan el duplicado |
| `NINGUNO` | — | Solo registra eventos. Útil para observar el tráfico real unos días antes de activar |

Eventos que **nunca** facturan:

- `reservation.created` — todavía no hay consumos.
- `reservation.cancelled` — queda registrado para revisión manual. Si ya se emitió factura, en
  Colombia eso se corrige con una **nota crédito** (`/v1/credit-notes` en Siigo), que es una
  decisión contable, no algo que convenga automatizar.

---

## El cron sigue siendo necesario

Tras el sexto intento MiniHotel marca la notificación como fallida y deja de insistir. Si el
VPS estuvo caído más de seis horas, esos eventos se perdieron para siempre.

El cron de las 00:10 **no se quita**: cambia de rol. Deja de ser el mecanismo principal y pasa
a ser la red de reconciliación que barre lo que el webhook no logró entregar. Como la
restricción única impide facturar dos veces, ambos mecanismos conviven sin pisarse.

---

## Configuración

```yaml
integrations:
  webhooks:
    enabled: true
    username: "${WEBHOOK_USERNAME}"        # credenciales dedicadas para MiniHotel
    password: "${WEBHOOK_PASSWORD}"
    disparador: OCUPACION
    facturacion-automatica: true           # interruptor de emergencia
    alertar-cancelaciones: true
    retener-dias: 90
    max-payload-chars: 60000
```

Las credenciales del webhook son **distintas de las del panel** a propósito: se pueden rotar
sin perder el acceso administrativo, y el usuario de MiniHotel solo puede llamar al endpoint
de recepción, no a los de sincronización ni a los de catálogos.

Si no configuras `username`/`password`, MiniHotel puede autenticarse con las credenciales del
panel. Es cómodo en desarrollo y desaconsejable en producción.

---

## Alta con MiniHotel

El endpoint no se registra desde su panel: hay que **escribirles** indicando la URL y las
credenciales de Basic Auth.

Requisitos previos:

1. VPS con **HTTPS público** y certificado válido.
2. El puerto abierto hacia MiniHotel.
3. Credenciales de webhook ya configuradas y desplegadas.

URL a entregarles:

```
https://tu-dominio.com/api/webhooks/minihotel
```

Verifica antes que responda:

```bash
curl -u minihotel:tu-clave https://tu-dominio.com/api/webhooks/minihotel/ping
```

---

## Puesta en marcha recomendada

Activarlo de golpe en producción es innecesariamente arriesgado. La secuencia sensata:

1. **Desplegar con `facturacion-automatica: false`.** Los eventos se registran pero no se
   factura nada. Deja pasar dos o tres días.
2. **Revisar los eventos** (`GET /api/webhooks/minihotel/events`): confirma que llegan los eventos esperados, que el
   `hotelCode` resuelve al hotel correcto y que los check-out aparecen como se espera.
3. **Activar en un solo hotel** ajustando su configuración, con el cron todavía corriendo.
4. **Comparar** durante una semana: las facturas del webhook deberían coincidir con las que el
   cron habría generado. El cron no duplicará nada gracias a la restricción única.
5. **Extender al resto**, dejando siempre el cron como reconciliación.

---

## Endpoints

### Recepción (usuario de webhook o de panel)

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `POST` | `/api/webhooks/minihotel` | Recibe la notificación |
| `GET` | `/api/webhooks/minihotel/ping` | Prueba de conectividad y configuración vigente |

### Administración (solo usuario del panel)

| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `GET` | `/api/webhooks/minihotel/events` | Lista de eventos |
| `GET` | `/api/webhooks/minihotel/events?estado=FALLIDO` | Filtra por estado |
| `GET` | `/api/webhooks/minihotel/events?reservationNumber=...` | Filtra por reserva |
| `GET` | `/api/webhooks/minihotel/events/{id}` | Detalle con payload completo |
| `POST` | `/api/webhooks/minihotel/events/{id}/reprocesar` | Reintenta a mano (síncrono) |
| `POST` | `/api/webhooks/minihotel/events/limpiar` | Borra los eventos vencidos |

---

## Códigos de respuesta

| Código | Cuándo | Efecto en MiniHotel |
|--------|--------|---------------------|
| `200` | Evento guardado, encolado o duplicado | Entregado; no reintenta |
| `200` | Evento que no dispara factura | Entregado; se conserva como traza |
| `400` | Cuerpo no interpretable o sin `notificationType` | Error permanente; reintentar no ayudaría |
| `503` | Módulo deshabilitado, o falló el guardado | Reintenta según su política |

El criterio de fondo: **se devuelve error solo cuando reintentar puede resolver algo**. Un
fallo al facturar en Siigo no se arregla reintentando la entrega, y sí generaría ruido.

---

## Estados de un evento

| Estado | Significado |
|--------|-------------|
| `RECIBIDO` | Guardado y en cola |
| `PROCESANDO` | Facturando en este momento |
| `PROCESADO` | Terminó en factura; el campo `syncTransactionId` enlaza con la auditoría |
| `IGNORADO` | No es disparador de facturación; el campo `motivo` explica por qué |
| `FALLIDO` | Falló; reintentable con `POST /api/webhooks/minihotel/events/{id}/reprocesar` |

---

## Consulta de eventos

No hay pantalla para los webhooks; los eventos se consultan por API con las credenciales del
panel:

| Método | Ruta | Uso |
|--------|------|-----|
| `GET`  | `/api/webhooks/minihotel/ping` | Estado del módulo |
| `GET`  | `/api/webhooks/minihotel/events` | Listado de eventos |
| `GET`  | `/api/webhooks/minihotel/events/{id}` | Detalle con el JSON completo |
| `POST` | `/api/webhooks/minihotel/events/{id}/reprocesar` | Reprocesar un evento |

---

## Problemas comunes

| Síntoma | Causa | Solución |
|---------|-------|----------|
| Todos los eventos en `FALLIDO` con "no hay ningún hotel configurado" | El `hotelCode` de MiniHotel no coincide con ningún `minihotel-auth.hotel-id` | Compara el `hotelCode` del payload con tu configuración |
| MiniHotel reporta *timeouts* | Algo síncrono se coló en el handler | El handler solo debe guardar y encolar |
| Facturas duplicadas | No debería ocurrir por la restricción única | Revisa que no se haya eliminado el índice `uq_reservation_hotel` |
| Llegan eventos pero no se factura nada | `facturacion-automatica: false`, o disparador que no corresponde | Revisa el `motivo` de los eventos ignorados |
| `401` en las entregas | Credenciales desactualizadas del lado de MiniHotel | Coordina la rotación con ellos antes de cambiarlas |
| La tabla crece mucho | Retención larga | Ajusta `retener-dias` o llama al endpoint de limpieza |
