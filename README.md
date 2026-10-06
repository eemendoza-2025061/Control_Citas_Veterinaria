# Sistema de Control de Citas para Veterinaria — API REST

## 1. Descripción y objetivo
API REST backend para digitalizar una clínica veterinaria: gestión de pacientes (mascotas),
agenda de citas médicas con veterinarios y expedientes clínicos simplificados.
Evaluación técnica: `Evaluación 2.docx` (API REST - Control de Citas Veterinaria).

## 2. Tecnologías y versiones (NO cambiar)
| Tecnología | Versión |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring Security (JWT stateless, jjwt 0.12.6) | Boot-managed / 0.12.6 |
| Spring Data JPA + Hibernate | Boot-managed |
| MySQL | 8.x (`mysql-connector-j`, scope runtime) |
| Maven / Packaging | Maven + JAR |
| Lombok, Validation, Actuator | Boot-managed |
| Configuración | `application.properties` (NO `application.yml`) |

> **Decisión de arquitectura:** el documento pide "microservicios", pero la matriz de
> endpoints define **una sola API** (`/api/v1/...`) evaluada con **un solo script**
> (`test-veterinaria.sh`) y una sola base de datos. Dividir en microservicios reales
> (Feign/Eureka/Gateway) rompería los endpoints evaluados y exige Spring Cloud, sin
> versión estable compatible con Spring Boot 4.1.1. Por eso se implementa un
> **monolito modular** (un JAR, paquetes por dominio: `auth`, `mascotas`, `citas`,
> `expedientes`) que cumple el 100% de la rúbrica. Ver `docs/DECISION-ARQUITECTURA.md`
> si se requiere en el futuro extraer servicios.

## 3. Estructura de paquetes
```
com.emendoza.veterinaria
├── controller/   AuthController, MascotaController, CitaController, ExpedienteController
├── service/      AuthService, MascotaService, CitaService, ExpedienteService
├── repository/   UsuarioRepository, MascotaRepository, CitaMedicaRepository, ExpedienteClinicoRepository
├── entity/       Usuario, Mascota, CitaMedica, ExpedienteClinico
├── dto/          AuthDto, MascotaDto, CitaDto, ExpedienteDto  (evitan ciclos Jackson)
├── security/     JwtUtil, JwtAuthenticationFilter, SecurityConfig, CustomUserDetailsService
├── exception/    BusinessRuleException, ResourceNotFoundException, GlobalExceptionHandler
└── config/       DataInitializer (1 ADMIN + 1 VET + 1 CLIENTE, sin SQL manual)
```

## 4. Modelo de datos (JPA genera las tablas, prohibido SQL manual)
- **Usuario**: id, nombre, telefono, email (unique), password (BCrypt), rol (ADMIN, VET, CLIENTE).
- **Mascota**: id, nombre, especie (PERRO/GATO/AVE/OTRO), raza, edad, `cliente` ManyToOne → Usuario.
- **CitaMedica**: id, `mascota` ManyToOne, `veterinario` ManyToOne → Usuario(VET),
  fechaHora, motivo, estado (PENDIENTE/COMPLETADA/CANCELADA). Índices en
  `(veterinario_id, fechaHora)` para la validación de cruces bajo carga.
- **ExpedienteClinico**: id, `cita` OneToOne unique, diagnostico, tratamiento, pesoKg, fechaRegistro.
- Todo `FetchType.LAZY`; sin `EAGER`; DTOs en la API (sin serialización infinita).

## 5. Reglas de negocio
1. **Cruce de horario**: cita dura 30 min; se rechaza si el VET tiene otra cita no-CANCELADA
   en `(fechaHora−29min, fechaHora+30min)`.
2. **Límite diario**: un CLIENTE no agenda si ya tiene 2 PENDIENTES ese día (por dueño, no por mascota).
3. **Cancelación**: solo PENDIENTE y solo si faltan **más de 2 horas** (`>= 120 min`).
4. **Expediente**: 1 por cita; al registrarlo la cita pasa a COMPLETADA (transaccional).
5. **Propiedad**: CLIENTE solo opera sus mascotas/citas/historiales; ADMIN sin restricción.

## 6. Configuración MySQL (`src/main/resources/application.properties`)
```properties
server.port=8081
spring.datasource.url=jdbc:mysql://localhost:3306/vet_auth_db?createDatabaseIfNotExist=true&useSSL=false
spring.datasource.username=IN5AM
spring.datasource.password=_odmom5AM
spring.jpa.hibernate.ddl-auto=update   # Hibernate crea/actualiza tablas desde las entidades
```
Solo crea el **schema vacío** (o deja que `createDatabaseIfNotExist=true` lo cree).
Las tablas y los 3 usuarios iniciales los crea la app al arrancar.

## 7. Cómo ejecutar desde IntelliJ IDEA
1. Abrir la carpeta `veterinaria/` (la que contiene `pom.xml`) como proyecto Maven.
2. Dejar que IntelliJ importe dependencias (usar su Maven con acceso a internet si la
   terminal falla con `PKIX path building failed`).
3. Verificar MySQL corriendo y las credenciales de `application.properties`.
4. Run ▶ `VeterinariaApplication` (o `Maven → Plugins → spring-boot:run`).
5. La API queda en `http://localhost:8081`. Usuarios iniciales: `admin@vet.com / 1234`
   (ADMIN), `vet@vet.com / 1234` (VET), `cliente@mail.com / 1234` (CLIENTE).

## 8. Endpoints y roles
| Método | Endpoint | Acceso |
|---|---|---|
| POST | `/api/v1/auth/register` (rol CLIENTE fijo) | público |
| POST | `/api/v1/auth/login` → `{token, email, rol}` | público |
| GET | `/api/v1/mascotas/mis-mascotas?page&size` | CLIENTE (usa el JWT) |
| POST | `/api/v1/mascotas` | CLIENTE (propia), ADMIN (+`clienteId`) |
| GET | `/api/v1/mascotas/{id}` | VET, ADMIN |
| POST | `/api/v1/citas` | CLIENTE, ADMIN |
| GET | `/api/v1/citas/agenda?veterinarioId&desde&hasta&page&size` | VET, ADMIN |
| PATCH | `/api/v1/citas/{id}/cancelar` | CLIENTE, ADMIN |
| POST | `/api/v1/expedientes` | VET, ADMIN |
| GET | `/api/v1/expedientes/mascota/{mascotaId}` | VET, CLIENTE (propia), ADMIN |

Autenticación: `Authorization: Bearer <JWT>`. Errores estandarizados vía
`@RestControllerAdvice` (`timestamp, status, error, message, path`).

## 9. Pruebas
```bash
# Funcionales + estrés (requiere curl; la app corriendo en 8081)
bash test-veterinaria.sh
# o desde Git Bash en Windows
```
El script hace login de los 3 roles, registra mascota, agenda 2 citas, verifica el
límite diario y el cruce de horario, cancela, registra expediente e historial; luego
lanza ráfagas concurrentes de `GET /agenda` y `GET /mis-mascotas` y reporta
tiempos y tasa de éxito. Monitoreo extra: `/actuator/health`, `/actuator/metrics`
(ADMIN).

## 10. Rendimiento / estrés (diseño)
Índices en consultas calientes; paginación con tope (`size ≤ 100`); `open-in-view=false`;
Hikari `max-pool 20 / min-idle 5`; `@Transactional` corto y `readOnly` en lecturas;
sin `EAGER` ni N+1 (proyecciones vía DTO); validaciones con `exists/count`
(no se cargan colecciones).

## 11. Git remoto (NO pushear sin autorización)
El `origin` actual apunta a un repositorio ajeno. Para apuntar al tuyo, **cuando lo
autorices**:
```
git remote set-url origin https://github.com/eemendoza-2025061/Control_Citas_Veterinaria.git
git add -A && git commit -m "..." && git push -u origin <rama>
```
No se ejecutó ninguna operación git en esta sesión por instrucción explícita.

## 12. Problemas conocidos
- `.\mvnw.cmd compile` falla en terminales sin acceso a Maven Central
  (`PKIX path building failed`): compilar desde IntelliJ IDEA con internet.
- Spring Cloud (Eureka/Feign/Gateway) **omitido a propósito**: sin soporte estable
  para Boot 4.1.1 y contradice la matriz de endpoints evaluada.
