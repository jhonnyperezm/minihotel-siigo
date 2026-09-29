---
name: java17-best-practices
description: >
  Guía de buenas prácticas para Java 17 y Spring Boot 3.x. SIEMPRE activa esta guía cuando el usuario
  pida escribir, revisar, refactorizar, crear clases, servicios, repositorios, controladores, entidades,
  DTOs, tests, o cualquier código Java. También actívala si el usuario menciona Spring Boot, JPA,
  Hibernate, Lombok, MapStruct, JUnit, Records, Sealed classes, o cualquier concepto Java moderno.
  Aplica estas reglas en cada respuesta que genere o modifique código Java, sin excepción.
---

# Java 17 + Spring Boot 3.x — Buenas Prácticas

Aplica estas reglas en **toda** generación o revisión de código Java. No son sugerencias opcionales: son el estándar de calidad del proyecto.

---

## 1. Características Modernas de Java 17

### Records (DTOs / POJOs inmutables)
Usa `record` en lugar de clases con campos, getters, equals/hashCode manuales cuando el objeto sea inmutable.

```java
// BIEN
public record CrearDocumentoRequest(String codigo, BigDecimal total, LocalDate fecha) {}

// MAL — innecesariamente verboso
public class CrearDocumentoRequest {
    private String codigo;
    private BigDecimal total;
    // getters, setters, equals, hashCode...
}
```

> Excepción: entidades JPA no pueden ser records (JPA requiere constructor sin args y mutabilidad).

### Sealed Classes
Usa `sealed` para modelar jerarquías cerradas y conocidas (estados, tipos de resultado, variantes de comando).

```java
public sealed interface ResultadoSync permits SyncExitoso, SyncFallido {}
public record SyncExitoso(String documentoId) implements ResultadoSync {}
public record SyncFallido(String error, Throwable causa) implements ResultadoSync {}
```

### Pattern Matching — `instanceof`
Elimina el cast manual después de `instanceof`.

```java
// BIEN
if (resultado instanceof SyncFallido fallo) {
    log.error("Falló: {}", fallo.error());
}

// MAL
if (resultado instanceof SyncFallido) {
    SyncFallido fallo = (SyncFallido) resultado;
}
```

### Switch Expressions
Usa switch como expresión (devuelve valor, sin fall-through).

```java
// BIEN
String tipo = switch (formaPago) {
    case CONTADO -> "CON";
    case CREDITO -> "CRE";
    default -> throw new IllegalArgumentException("Forma de pago desconocida: " + formaPago);
};

// MAL — switch statement antiguo con break
```

### Text Blocks
Para JSON, SQL, o XML inline usa text blocks.

```java
String query = """
        SELECT e.id, e.nombre
        FROM esquema.empresa e
        WHERE e.activo = true
        """;
```

### `var` — inferencia de tipo local
Úsalo cuando el tipo ya es obvio en el lado derecho; evítalo cuando oscurece el tipo.

```java
// BIEN — tipo evidente
var cliente = new RestTemplate();
var lista = new ArrayList<String>();

// MAL — tipo no es claro
var x = servicio.obtener();
```

---

## 2. Calidad y Estilo de Código

- **Un nivel de abstracción por método**: si un método hace I/O Y lógica de negocio, divídelo.
- **Nombres en español** para el dominio del negocio; nombres en inglés para infraestructura técnica (config, util).
- **Métodos de máx. 20 líneas**; si es más largo, extraer.
- **No comentarios obvios**: el código debe explicarse solo. Comenta solo el *porqué*, nunca el *qué*.
- **No `null` como valor de retorno** — usa `Optional<T>` en la capa de servicio cuando el resultado puede no existir.

```java
// BIEN
public Optional<TerceroPojo> buscarPorNit(String nit) { ... }

// MAL
public TerceroPojo buscarPorNit(String nit) { return null; } // ← nunca retornar null
```

---

## 3. Lombok y MapStruct

### Lombok — reglas de uso
| Anotación | Cuándo usar |
|-----------|-------------|
| `@Getter` / `@Setter` | Entidades JPA y POJOs mutables |
| `@Builder` | Construcción de objetos con muchos campos |
| `@RequiredArgsConstructor` | Inyección de dependencias por constructor (Spring) |
| `@Slf4j` | Logger en servicios y clientes |
| `@Data` | **Evitar en entidades JPA** — genera `equals`/`hashCode` basados en todos los campos, causando problemas con proxies Hibernate |

```java
// BIEN — entidad JPA
@Entity
@Getter
@Setter
@Table(name = "documento", schema = "esquema")
public class Documento { ... }

// MAL en entidades
@Data  // ← peligroso con Hibernate
public class Documento { ... }
```

### MapStruct — reglas de uso
- Define mappers como `@Mapper(componentModel = "spring")`.
- Especifica explícitamente mappings con nombres distintos con `@Mapping`.
- Nunca hagas conversiones complejas de negocio dentro del mapper — solo transformación de estructura.

```java
@Mapper(componentModel = "spring")
public interface DocumentoMapper {
    @Mapping(source = "nroDocumento", target = "numero")
    @Mapping(source = "fechaCreacion", target = "fecha", dateFormat = "yyyy-MM-dd")
    DocumentoPojo toDto(Documento entity);
}
```

---

## 4. Spring Boot 3.x

### Inyección de Dependencias
**Siempre por constructor**, no por campo (`@Autowired` en campo está deprecated en la práctica).

```java
// BIEN
@Service
@RequiredArgsConstructor
public class IntegrationService {
    private final MiniHotelClient miniHotelClient;
    private final WorldOfficeDocumentoClient woClient;
}

// MAL
@Service
public class IntegrationService {
    @Autowired
    private MiniHotelClient miniHotelClient; // ← dificulta testing
}
```

### Transacciones
- `@Transactional(readOnly = true)` en todos los métodos de solo lectura (enruta a réplica).
- `@Transactional` solo en métodos que escriben.
- No anidar transacciones sin entender la propagación; usa `Propagation.REQUIRES_NEW` con cuidado.

```java
@Transactional(readOnly = true)
public List<DocumentoPojo> listar(FiltroWo filtro) { ... }

@Transactional
public ResultadoAccionPojo crear(CrearDocumentoRequest req) { ... }
```

### Configuration Properties
Prefiere `@ConfigurationProperties` sobre `@Value` para grupos de propiedades relacionadas.

```java
@ConfigurationProperties(prefix = "integrations.worldoffice")
public record WorldOfficeProperties(String baseUrl, String token, boolean authEnabled) {}
```

### REST Controllers
- Nombre: `Rest[Feature]` (convención del proyecto).
- Retorna siempre `ResponseEntity<ResultadoAccionPojo>` o el wrapper del proyecto.
- Valida entrada con Bean Validation (`@Valid`, `@NotNull`, `@NotBlank`).
- No lógica de negocio en controllers — solo orquestar llamadas al servicio.

---

## 5. JPA / Hibernate

- **No usar `@GeneratedValue(strategy = IDENTITY)`** si la BD usa secuencias — usar `SEQUENCE`.
- **Evitar `FetchType.EAGER`** — causa N+1 queries. Usar `LAZY` + `JOIN FETCH` en queries específicas.
- **No usar `findAll()`** para colecciones grandes — paginar siempre con `PaginacionWo`/`Pageable`.
- **Batch updates**: configurar `hibernate.jdbc.batch_size` (el proyecto usa 500).
- **Nunca llamar a la BD dentro de un loop** — cargar en memoria antes del loop o usar una query con `IN`.

```java
// MAL — N+1 query
for (Reservacion r : reservaciones) {
    Tercero t = terceroRepo.findById(r.getTerceroId()); // ← query por iteración
}

// BIEN — query única
Map<Long, Tercero> terceros = terceroRepo.findAllById(ids)
    .stream().collect(Collectors.toMap(Tercero::getId, Function.identity()));
```

---

## 6. Manejo de Excepciones

- Usa excepciones **específicas del dominio** que extiendan `RuntimeException`.
- Nunca captures `Exception` genérica — captura la excepción más específica posible.
- No silencies excepciones (`catch (Exception e) {}`).
- En integraciones externas, envuelve excepciones de infraestructura en excepciones de dominio.

```java
// BIEN
public class ReservacionNoEncontradaException extends RuntimeException {
    public ReservacionNoEncontradaException(String id) {
        super("Reservación no encontrada: " + id);
    }
}

// MAL
try {
    cliente.obtener(id);
} catch (Exception e) {
    // silencio — ¡peligroso!
}
```

---

## 7. Thread Safety y Multi-Tenancy

- **`ThreadLocal`** para contexto por hilo (ej. `HotelContextService`) — siempre limpiar en `finally`.
- **Variables compartidas mutables** en beans Singleton: usar `volatile` o tipos concurrentes (`ConcurrentHashMap`, `AtomicReference`).
- **No usar campos de instancia mutables en `@Service` o `@Component`** para estado de request.

```java
// PATRÓN obligatorio con ThreadLocal
try {
    hotelContextService.setCurrentHotel(hotelKey);
    integrationService.sync(fecha);
} finally {
    hotelContextService.clearCurrentHotel(); // ← SIEMPRE en finally
}
```

---

## 8. Seguridad

- **No loguear datos sensibles**: tokens, contraseñas, NITs, números de tarjeta.
- **Sanitizar inputs externos** antes de usarlos en queries (usar parámetros JPA, nunca concatenar SQL).
- **No hardcodear credenciales** — siempre desde `application.yml` / variables de entorno.
- Tokens y secretos en propiedades con `${ENV_VAR}` y nunca en el código fuente.

```java
// MAL
log.info("Token: {}", token); // ← expone credenciales en logs

// BIEN
log.info("Autenticando con WO para hotel: {}", hotelKey);
```

---

## 9. Testing con JUnit 5

- Un test = una sola responsabilidad. Nombre del método: `deberiaX_cuandoY()`.
- Usar `@ExtendWith(MockitoExtension.class)` para unit tests con mocks.
- Preferir `@SpringBootTest` + `@Transactional` para tests de integración (rollback automático).
- Usar `AssertJ` (`assertThat`) en lugar de `assertEquals` — más legible.
- Cubrir: camino feliz + al menos un caso de error.

```java
@Test
void deberiaCrearDocumento_cuandoReservacionEsValida() {
    // Arrange
    var reservacion = crearReservacionMock();
    when(woClient.crear(any())).thenReturn(respuestaExitosa());

    // Act
    var resultado = service.syncFactura(reservacion);

    // Assert
    assertThat(resultado).isNotNull();
    assertThat(resultado.getDocumentoId()).isNotBlank();
}
```

---

## 10. Performance

- **Caché de catálogos**: datos maestros que no cambian frecuentemente deben cargarse en memoria al startup (`@PostConstruct`) y refrescarse solo cuando hay cache miss.
- **Llamadas HTTP a sistemas externos**: nunca dentro de transacciones de BD abiertas.
- **Async para operaciones fire-and-forget**: usa `@Async` o `CompletableFuture` para operaciones como contabilización que no bloquean el flujo principal.
- **Streams vs loops**: usa streams para transformaciones, loops clásicos para acumulación con efectos secundarios.

```java
// BIEN — async fire-and-forget
@Async
public CompletableFuture<Void> contabilizarDocumentoAsync(String id) {
    woClient.contabilizar(id);
    return CompletableFuture.completedFuture(null);
}
```

---

## Resumen Rápido — Lista de Verificación

Antes de generar o aprobar código Java, verificar:

- [ ] ¿Puede ser un `record` en lugar de una clase mutable?
- [ ] ¿La inyección de dependencias es por constructor?
- [ ] ¿Los métodos de lectura tienen `@Transactional(readOnly = true)`?
- [ ] ¿Se usa `Optional` en lugar de retornar `null`?
- [ ] ¿Se limpian los `ThreadLocal` en `finally`?
- [ ] ¿No hay N+1 queries ocultas?
- [ ] ¿No se loguean datos sensibles?
- [ ] ¿Las excepciones son específicas del dominio?
- [ ] ¿El `switch` usa la forma de expresión moderna?
- [ ] ¿Lombok usa `@Getter`/`@Setter` en entidades (no `@Data`)?
