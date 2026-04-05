# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CertManagerCloud (branded as CertGuard) is a multi-tenant SaaS platform for SSL/TLS certificate monitoring and management. Organizations register monitored hosts (Targets), distributed agents perform private network scans, and results are stored as CertificateRecords. Built with Spring Boot 4.0.3, Java 17, PostgreSQL, and Docker Compose.

For a deep-dive on database design, JPA configuration, Docker architecture, and REST conventions, see `ARCHITECTURE.md`.

## Build & Run Commands

```bash
# Build JAR (skip tests)
mvn clean package -DskipTests

# Build JAR with tests
mvn clean package

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=ClassName

# Start full stack (PostgreSQL, RabbitMQ, App, Prometheus, Grafana)
docker-compose up -d

# View app logs
docker-compose logs -f certguard-app

# Stop all services
docker-compose down

# Wipe all data (fresh DB)
docker-compose down -v

# Run locally without Docker (requires PostgreSQL on localhost:5432)
APP_DEV_MODE=true mvn spring-boot:run
```

Access points when running via Docker Compose:
- App (HTTPS): `https://localhost:58244`
- Swagger UI: `https://localhost:58244/swagger-ui.html`
- RabbitMQ Management: `http://localhost:15672`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`

## Architecture

### Three-Layer Pattern (strict, no skipping layers)
```
Controller (@RestController) → Service (@Service) → Repository (JpaRepository) → PostgreSQL
```

### Key Packages (`src/main/java/com/codecatalyst/`)
- `entity/` — JPA entities extending `BaseEntity` (UUID PK, created_at, updated_at)
- `repository/` — Spring Data JPA interfaces, all DB access
- `service/` — Business logic; class-level `@Transactional(readOnly = true)`, write methods override with `@Transactional`
- `controller/` — REST endpoints; no business logic, no try/catch (exceptions bubble to handler)
- `dto/request/` and `dto/response/` — MapStruct-mapped DTOs
- `security/` — JWT filter chain, agent key auth, TenantContext, BouncyCastle CA
- `config/` — SecurityConfig, FlywayConfig, WebMvcConfig
- `exception/` — `GlobalExceptionHandler` (@RestControllerAdvice) maps exceptions to RFC 9457 ProblemDetail
- `enums/` — `UserRole`, `CertStatus`, `AgentStatus`, `ScanJobStatus`, `HostType`, `SubscriptionStatus`

### Multi-Tenancy
All data is organization-scoped. `TenantContext` (ThreadLocal) stores `orgId`/`userId` per request, set by `JwtAuthenticationFilter` and cleared in `finally`. All queries filter by `org_id`.

### Authentication (two separate filter chains)
1. **User JWT** (`JwtAuthenticationFilter`): `Authorization: Bearer <token>` header → JJWT 0.12.6 HS256 validation → `CertGuardUserPrincipal` in SecurityContext
2. **Agent key** (`AgentAuthFilter`): `X-Agent-Key` + `X-Agent-Id` headers → BCrypt hash comparison → sets `authenticatedAgent` request attribute

Filter order: `AgentAuthFilter` → `JwtAuthenticationFilter` → Spring Security filters.

### Database
- **Flyway** owns schema (`src/main/resources/db/migration/V*.sql`); Hibernate is `ddl-auto: none`
- All entities use `GenerationType.UUID`; all `@ManyToOne` use `FetchType.LAZY`
- PostgreSQL ENUMs are used for roles and status fields
- `org_id` is denormalized on `certificate_records` and `agent_scan_jobs` for query performance
- Timestamps managed by both Hibernate `@CreationTimestamp`/`@UpdateTimestamp` and DB `BEFORE UPDATE` triggers

### REST API Conventions
- Versioned under `/api/v1/`
- Multi-tenant URL hierarchy: `/api/v1/org/{orgId}/targets/{targetId}/...`
- Errors use RFC 9457 `ProblemDetail` format
- `QuotaExceededException` → HTTP 429; `ResourceNotFoundException` → 404; `IllegalStateException` → 409

## Configuration

`src/main/resources/application.yml` — all sensitive values come from environment variables with local dev defaults. Key variables:

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `JWT_SECRET` | HMAC-SHA256 secret (min 64 chars) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | OAuth2 (only needed when `APP_DEV_MODE=false`) |
| `APP_DEV_MODE` | `true` disables OAuth2 and enables `DevAuthController` test login |

App listens on **HTTPS port 8443** (PKCS12 keystore at `/opt/certguard/certs/certguard.p12`). Docker Compose maps this to host port 58244.

## Dev Mode

Set `APP_DEV_MODE=true` (default in `application.yml`) to:
- Skip Google OAuth2 (no credentials needed)
- Enable `DevAuthController` for test JWT generation
- Use hardcoded JWT secret default

## Adding New Features

**New REST endpoint:**
1. Add DTO classes in `dto/request/` and `dto/response/`
2. Add MapStruct mapper interface
3. Add service method (with `@Transactional` for writes)
4. Add controller method

**New entity/table:**
1. Create JPA entity extending `BaseEntity`
2. Create `JpaRepository` interface
3. Add Flyway migration `V<N>__description.sql` in `src/main/resources/db/migration/`

**New scheduled job:**
Use `@Scheduled(cron = "...")` on a `@Service` method — `@EnableScheduling` is on `CertGuardApplication`.
