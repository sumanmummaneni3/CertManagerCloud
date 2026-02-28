# CertMonitor — Architecture Documentation

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Technology Stack](#2-technology-stack)
3. [Code Structure](#3-code-structure)
4. [Application Layers](#4-application-layers)
5. [Database Design](#5-database-design)
6. [Spring JPA & Hibernate Configuration](#6-spring-jpa--hibernate-configuration)
7. [Docker & Container Architecture](#7-docker--container-architecture)
8. [REST API Design](#8-rest-api-design)
9. [Error Handling](#9-error-handling)
10. [Key Design Decisions](#10-key-design-decisions)
11. [Security Considerations](#11-security-considerations)

---

## 1. Project Overview

CertMonitor is a multi-tenant REST API service for tracking SSL/TLS certificate records across an organisation's infrastructure. Each organisation manages a set of monitored hosts (Targets), and certificate scan results are stored as CertificateRecords against those targets. Users belong to an organisation and carry a role that controls their access level.

The service is built on Spring Boot 3.5.8 with Spring Data JPA backed by a PostgreSQL 16 database, and the entire stack runs as Docker containers orchestrated by Docker Compose.

---

## 2. Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 21 (LTS) |
| Framework | Spring Boot | 3.5.8 |
| Persistence | Spring Data JPA + Hibernate | Managed by Boot parent |
| Database | PostgreSQL | 16 (Alpine) |
| Connection Pool | HikariCP | Managed by Boot parent |
| Web server | Apache Tomcat (embedded) | 10.1.40 (pinned) |
| Boilerplate reduction | Lombok | Managed by Boot parent |
| Containerisation | Docker + Docker Compose | — |
| Build tool | Maven | Wrapper included |

---

## 3. Code Structure

```
certmonitor/
├── Dockerfile                          # Multi-stage Spring Boot image
├── docker-compose.yml                  # Full-stack orchestration (DB + App)
├── pom.xml                             # Maven build descriptor
├── .env.example                        # Environment variable template
├── .gitignore
│
└── src/
    └── main/
        ├── java/com/certmonitor/
        │   │
        │   ├── CertMonitorApplication.java        # Bootstrap entry point
        │   │
        │   ├── entity/                            # JPA-mapped domain objects
        │   │   ├── Organization.java
        │   │   ├── User.java
        │   │   ├── Target.java
        │   │   └── CertificateRecord.java
        │   │
        │   ├── enums/
        │   │   └── UserRole.java                  # ADMIN | READ_ONLY
        │   │
        │   ├── repository/                        # Spring Data JPA interfaces
        │   │   ├── OrganizationRepository.java
        │   │   ├── UserRepository.java
        │   │   ├── TargetRepository.java
        │   │   └── CertificateRecordRepository.java
        │   │
        │   ├── service/                           # Business logic
        │   │   ├── OrganizationService.java
        │   │   ├── UserService.java
        │   │   ├── TargetService.java
        │   │   └── CertificateRecordService.java
        │   │
        │   ├── controller/                        # REST endpoints
        │   │   ├── OrganizationController.java
        │   │   ├── UserController.java
        │   │   ├── TargetController.java
        │   │   └── CertificateRecordController.java
        │   │
        │   └── exception/                         # Error handling
        │       ├── ResourceNotFoundException.java
        │       └── GlobalExceptionHandler.java
        │
        └── resources/
            └── application.yml                    # Application configuration
```

---

## 4. Application Layers

The application follows a strict three-layer architecture. Each layer has a single responsibility and may only call the layer directly beneath it.

```
  HTTP Request
       │
       ▼
┌─────────────┐
│  Controller │  Receives HTTP, delegates to Service, returns HTTP response
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Service   │  Owns business logic, transaction boundaries, validation rules
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Repository  │  Spring Data JPA interfaces — all DB access goes through here
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  PostgreSQL │  Persistent store
└─────────────┘
```

### Controller Layer

Controllers are annotated with `@RestController` and handle only HTTP concerns: reading path variables and request bodies, calling the appropriate service method, and returning the correct HTTP status code. They contain no business logic. All four controllers follow resource-scoped URL patterns (e.g., `/api/v1/organizations/{orgId}/targets`), which naturally enforces the organisational hierarchy at the URL level.

### Service Layer

Services carry the `@Service` annotation and own all business rules. Every service class is annotated with `@Transactional(readOnly = true)` at the class level, which means all read methods automatically run in a read-only transaction. Write methods override this with `@Transactional` at the method level. This is the most efficient pattern: read-only transactions skip dirty-checking and allow the database to optimise accordingly.

Services validate business rules before persisting — for example, `OrganizationService` checks for duplicate names, `TargetService` checks for duplicate host/port combinations per organisation, and `UserService` checks for duplicate email addresses. This layer also resolves cross-entity relationships: when creating a `User`, the service fetches the parent `Organization` and links it before saving.

### Repository Layer

Repositories extend `JpaRepository<Entity, UUID>`, which provides standard CRUD operations out of the box. Custom query methods are defined in two ways: Spring Data's derived query naming convention (e.g., `findByOrganizationIdAndRole`) for simple lookups, and `@Query` with JPQL for more expressive queries such as finding certificates expiring before a given date.

---

## 5. Database Design

### Entity Relationship Diagram

```
┌───────────────────────┐
│      organization     │
│─────────────────────  │
│ id (PK, UUID)         │◄──────────────────────────┐
│ name                  │                           │
│ keystore_location     │◄──────────┐               │
│ api_key (UNIQUE)      │           │               │
│ created_at            │           │               │
│ updated_at            │           │               │
└───────────┬───────────┘           │               │
            │ 1                     │               │
            │                       │               │
    ┌───────┴────────┐              │               │
    │                │              │               │
    ▼ *              ▼ *            │               │
┌──────────┐  ┌─────────────┐      │               │
│   user   │  │   target    │      │               │
│──────────│  │─────────────│      │               │
│ id (PK)  │  │ id (PK)     │      │               │
│ org_id   │  │ org_id (FK) │──────┘               │
│ email    │  │ host        │                      │
│ role     │  │ port        │                      │
│ created_at│  │ is_private  │                      │
│ updated_at│  │ created_at  │                      │
└──────────┘  │ updated_at  │                      │
              └──────┬──────┘                      │
                     │ 1                            │
                     │                              │
                     ▼ *                            │
              ┌─────────────────────┐              │
              │  certificate_record │              │
              │─────────────────────│              │
              │ id (PK)             │              │
              │ target_id (FK)      │              │
              │ org_id (FK)  ───────┼──────────────┘
              │ common_name         │
              │ issuer              │
              │ expiry_date         │
              │ client_org_name     │ (nullable)
              │ division_name       │ (nullable)
              │ status              │
              │ created_at          │
              │ updated_at          │
              └─────────────────────┘
```

### Table Descriptions

**organization** is the root tenant entity. Every other table has a foreign key back to it. The `api_key` column is unique across the system and is the intended authentication vector for API callers. The `keystore_location` stores a path or URI to the keystore used when making TLS connections to private targets.

**user** (quoted in SQL and JPA because `user` is a reserved PostgreSQL keyword) stores the people who can interact with the system. A user belongs to exactly one organisation. The `role` column is enforced as a PostgreSQL ENUM type (`ADMIN` | `READ_ONLY`) in the schema and as a Java enum in the JPA entity.

**target** represents a host/port combination that the system monitors. The combination of `(org_id, host, port)` is unique, enforced by both a database-level unique constraint and an application-level check in `TargetService`. The `is_private` flag indicates whether the target sits behind a private network and requires the organisation's keystore to connect.

**certificate_record** holds individual certificate scan results. It carries two foreign keys — `target_id` and `org_id` — allowing efficient queries by either dimension. The `org_id` denormalisation avoids the need for a JOIN through the `target` table when querying all certificates for an organisation. The optional fields `client_org_name` and `division_name` accommodate X.509 Subject fields that may or may not be present.

### Indexes

The following indexes are created in addition to primary keys and unique constraints:

| Table | Index | Purpose |
|---|---|---|
| `user` | `idx_user_org_id` | Fast lookup of all users per org |
| `target` | `idx_target_org_id` | Fast lookup of all targets per org |
| `certificate_record` | `idx_cert_target_id` | Fast lookup of certs per target |
| `certificate_record` | `idx_cert_org_id` | Fast lookup of certs per org |
| `certificate_record` | `idx_cert_expiry` | Fast range queries for expiry alerting |

### Timestamps and the `updated_at` Trigger

Every table carries `created_at` and `updated_at` columns of type `TIMESTAMPTZ`. These are managed in two places for defence in depth. At the application layer, Hibernate's `@CreationTimestamp` and `@UpdateTimestamp` annotations set these fields automatically before each insert or update. At the database layer, a `BEFORE UPDATE` trigger on every table independently sets `updated_at = NOW()`. This means the timestamps remain accurate even if rows are modified by a tool or script that bypasses the application entirely.

---

## 6. Spring JPA & Hibernate Configuration

Configuration lives in `src/main/resources/application.yml`.

### DataSource

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:cert_monitor}
    username: ${DB_USER:cert_admin}
    password: ${DB_PASSWORD:change_me_to_a_strong_password}
    driver-class-name: org.postgresql.Driver
```

All connection parameters are externalised as environment variables with safe local defaults. The application never has credentials baked into any committed file.

### HikariCP Connection Pool

```yaml
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000   # 30 seconds
      idle-timeout: 600000        # 10 minutes
```

HikariCP is the default connection pool in Spring Boot and is one of the highest-performance JDBC pools available. The pool is configured to maintain a minimum of 2 idle connections (avoiding cold-start latency on the first requests after a quiet period) up to a maximum of 10. The idle timeout of 10 minutes reclaims connections that are no longer needed, which is important in a containerised environment where the database may impose its own connection limits.

### Hibernate / JPA

```yaml
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true
        default_schema: public
```

`ddl-auto: validate` is the most important setting here. Hibernate will check that the database schema matches the entity mappings on startup and will throw an error if there is a mismatch — but it will never alter or create tables. Schema changes are the exclusive responsibility of `schema.sql`, which runs inside the database container. This separation of concerns prevents Hibernate from making destructive or unintended schema changes in production.

`show-sql: false` keeps logs clean in production. Set this to `true` only during local development when debugging query behaviour.

The `PostgreSQLDialect` tells Hibernate to generate SQL syntax, functions, and type mappings appropriate for PostgreSQL rather than a generic SQL-92 baseline.

### Entity Mapping Highlights

**Primary keys** use `@GeneratedValue(strategy = GenerationType.UUID)`, which delegates UUID generation to Hibernate (using a version 4 random UUID). This matches the `DEFAULT uuid_generate_v4()` clause in the PostgreSQL schema.

**Relationships** on the child side (e.g., `User.organization`, `CertificateRecord.target`) use `FetchType.LAZY`. This means Hibernate does not load the parent entity when loading a child unless it is explicitly accessed in code. Eager fetching of all relationships would cause N+1 query problems at scale, so lazy loading is the safe default for `@ManyToOne` associations.

**Cascades** are declared only on the parent side (`Organization` → `User`, `Organization` → `Target`, `Target` → `CertificateRecord`) with `CascadeType.ALL` and `orphanRemoval = true`. This means deleting an organisation automatically deletes all of its users, targets, and certificate records without requiring explicit application code to do so.

**The `user` table name** is wrapped in escaped quotes (`@Table(name = "\"user\"")`). `user` is a reserved word in PostgreSQL and most SQL dialects, so without the quotes, Hibernate's generated SQL would fail or reference the current database session user rather than the application table.

**Enum persistence** on the `User.role` field uses `@Enumerated(EnumType.STRING)`. This stores the string value `ADMIN` or `READ_ONLY` in the column rather than an integer ordinal. String storage is more resilient to refactoring: reordering the enum values in Java would corrupt ordinal-stored data, whereas string values remain stable.

---

## 7. Docker & Container Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    Docker Compose Network                │
│                                                          │
│  ┌────────────────────┐       ┌──────────────────────┐  │
│  │   cert_monitor_app │       │   cert_monitor_db    │  │
│  │   (Spring Boot)    │──────►│   (PostgreSQL 16)    │  │
│  │   Port: 8080       │       │   Port: 5432         │  │
│  └────────────────────┘       └──────────┬───────────┘  │
│                                          │              │
└──────────────────────────────────────────┼──────────────┘
                                           │
                                  ┌────────▼────────┐
                                  │  postgres_data  │
                                  │  (named volume) │
                                  └─────────────────┘
```

### Spring Boot Dockerfile (Multi-Stage)

The Dockerfile uses a two-stage build to minimise the final image size and attack surface.

**Stage 1 — Builder** uses `eclipse-temurin:21-jdk-alpine`. The Maven wrapper and `pom.xml` are copied first, and `dependency:go-offline` is run before copying any source code. This exploits Docker's layer cache: if the `pom.xml` has not changed, the dependency download layer is reused on rebuild, making subsequent builds significantly faster. Source is copied and compiled in a second layer.

**Stage 2 — Runtime** uses `eclipse-temurin:21-jre-alpine`. The JRE image is substantially smaller than the JDK image (no compiler, no development tools). Only the compiled JAR is copied from the builder stage — none of the source, Maven files, or intermediate build artefacts are present in the final image. A dedicated non-root user (`appuser`) is created and used for the runtime process, which limits the blast radius if the container is compromised.

### Startup Ordering

The `app` service in `docker-compose.yml` declares:

```yaml
depends_on:
  db:
    condition: service_healthy
```

This means Docker Compose will not start the Spring Boot container until the `db` container passes its healthcheck (`pg_isready`). Without this, the application would start, fail to connect, and crash before the database is ready to accept connections.

### Configuration via Environment Variables

No credentials or environment-specific values are baked into the application image. All runtime configuration is injected via environment variables at container start. The `application.yml` uses `${VARIABLE:default}` syntax, which means the app can also run locally without Docker by relying on the defined defaults.

---

## 8. REST API Design

All routes are versioned under `/api/v1/`. The URL hierarchy mirrors the domain hierarchy — users and targets are always accessed under their parent organisation, which makes the tenant boundary explicit in the URL.

```
/api/v1/
│
├── organizations/                              Organization CRUD
│   ├── {orgId}/users/                          User CRUD (scoped to org)
│   │   └── ?role=ADMIN|READ_ONLY               Filter by role
│   └── {orgId}/targets/                        Target CRUD (scoped to org)
│       └── {targetId}/certificates/            Certificate creation & target-scoped listing
│
└── {orgId}/certificates/                       Org-wide certificate listing & management
    └── ?expiringWithinDays=N                   Expiry window filter
```

HTTP status codes follow REST conventions: `200 OK` for successful reads, `201 Created` for successful POST operations, `204 No Content` for successful deletes, `400 Bad Request` for validation failures, and `404 Not Found` for missing resources.

---

## 9. Error Handling

All exceptions are centralised in `GlobalExceptionHandler`, which is annotated with `@RestControllerAdvice`. This means no controller needs to contain any try/catch logic.

Error responses use Spring's `ProblemDetail` format (RFC 9457), which is a standardised JSON structure for HTTP error responses. An example error response looks like:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Organization not found: 3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

Three exception types are handled:

- `ResourceNotFoundException` — thrown by service methods when a looked-up entity does not exist. Maps to `404`.
- `IllegalArgumentException` — thrown by service methods for business rule violations (duplicates, invalid state). Maps to `400`.
- `MethodArgumentNotValidException` — thrown automatically by Spring when a `@Valid`-annotated request body fails Bean Validation. Maps to `400` with a comma-separated list of field-level errors.

---

## 10. Key Design Decisions

### UUIDs as Primary Keys

Integer auto-increment IDs were rejected in favour of UUID v4 primary keys. The reasons are: UUIDs can be generated without a database round-trip; they do not leak record count or insertion order to API callers; and they are safe to use across distributed systems or if data is ever migrated or merged between environments. The trade-off is slightly larger index size, which is acceptable at the expected data volumes for this system.

### Schema Ownership Belongs to SQL, Not Hibernate

Setting `ddl-auto: validate` rather than `create`, `update`, or `create-drop` is a deliberate choice. The `schema.sql` file is the single authoritative source for the database structure. This approach means schema changes are explicit, reviewable, and version-controlled independently of the application code. It also prevents Hibernate from making unintended or destructive changes to a production database.

### Read-Only Transactions as the Default

All service classes are annotated with `@Transactional(readOnly = true)` at the class level, with write methods overriding to `@Transactional`. Read-only transactions allow Hibernate to skip dirty-checking of loaded entities (a non-trivial performance saving when loading many objects), and give the database driver the opportunity to route reads to a read replica if one exists in future.

### Lazy Loading on All Relationships

All `@ManyToOne` associations use `FetchType.LAZY`. The default for `@ManyToOne` in JPA is actually `EAGER`, which would silently issue additional SELECT statements for every related entity whenever a child is loaded. Explicitly declaring `LAZY` prevents this and gives the service layer full control over when related data is fetched.

### Denormalised `org_id` on `certificate_record`

`certificate_record` holds both a `target_id` FK and an `org_id` FK. Technically, `org_id` could be derived by joining through `target → org_id`. The denormalisation was chosen deliberately: querying all certificates for an organisation (a very common operation for dashboards and expiry alerts) requires no JOIN, the `idx_cert_org_id` index makes it fast, and the foreign key constraint ensures it cannot become inconsistent with the parent target's organisation. The trade-off is that if a target were ever moved to a different organisation (an unlikely but possible future operation), both FKs would need to be updated together.

### Duplicate Protection at Two Levels

Business uniqueness rules (duplicate org name, duplicate email, duplicate target host/port) are enforced both in the service layer (application-level check returning a clear error message) and at the database level (unique constraints in the schema). The application-level check gives the caller a descriptive `400` response. The database constraint is the safety net in case two concurrent requests both pass the application check simultaneously. Neither layer alone is sufficient.

### Non-Root Container User

The Spring Boot runtime container runs as a non-root user (`appuser`). If a vulnerability were exploited in the application or its dependencies, the attacker would have only the privileges of that unprivileged user inside the container, rather than root access to the container filesystem.

### Explicit Tomcat Version Pin

`spring-boot-starter-web` bundles an embedded Tomcat server. Even when upgrading the Spring Boot parent version, the managed Tomcat version may lag behind security patch releases. The `<tomcat.version>10.1.40</tomcat.version>` property in the `pom.xml` overrides the Boot-managed version to ensure known CVEs (CVE-2024-56337, CVE-2024-50379) in earlier Tomcat releases are not present in the built image.

---

## 11. Security Considerations

The following security properties are built into the current design. They represent a baseline; a production deployment would likely add authentication middleware (e.g., Spring Security with JWT or OAuth2) and additional hardening.

- **No credentials in source code.** All secrets are environment variables loaded at runtime from a `.env` file that is gitignored.
- **Database schema is append-only from the application's perspective.** With `ddl-auto: validate`, the application can read and write data but cannot drop or alter tables.
- **Unique `api_key` per organisation.** The `api_key` column is designed as the future authentication token for org-scoped API calls, with a unique constraint enforced at the database level.
- **Tomcat pinned to a CVE-free version.** See Section 10 above.
- **Non-root container process.** Reduces privilege escalation risk if the container is compromised.
- **HikariCP connection pool.** Connections are reused and never left dangling; the pool enforces a maximum connection count, protecting the database from connection exhaustion under load.
