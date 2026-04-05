# CertGuard Cloud — Architecture Documentation

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Technology Stack](#2-technology-stack)
3. [Code Structure](#3-code-structure)
4. [Application Layers](#4-application-layers)
5. [Database Design](#5-database-design)
6. [Spring JPA & Hibernate Configuration](#6-spring-jpa--hibernate-configuration)
7. [Security & Authentication](#7-security--authentication)
8. [Docker & Container Architecture](#8-docker--container-architecture)
9. [REST API Design](#9-rest-api-design)
10. [Error Handling](#10-error-handling)
11. [Key Design Decisions](#11-key-design-decisions)

---

## 1. Project Overview

CertGuard Cloud is a multi-tenant SaaS platform for SSL/TLS certificate monitoring and management. Each organisation manages a set of monitored hosts (Targets), certificate scan results are stored as CertificateRecords against those targets, and distributed agents perform scans on private networks that the server cannot reach directly. Users authenticate via Google OAuth2 (production) or a dev-mode JWT bypass, and all data is strictly isolated by organisation.

The service is built on Spring Boot 4.0.3 with Java 17, Spring Data JPA backed by PostgreSQL 15, Flyway for schema management, and the full stack runs as Docker Compose containers. The application serves both a REST API and a bundled React SPA from the same process.

---

## 2. Technology Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 17 (LTS) |
| Framework | Spring Boot | 4.0.3 |
| Persistence | Spring Data JPA + Hibernate | Managed by Boot parent |
| Database | PostgreSQL | 15 (Alpine) |
| Schema migrations | Flyway | 11.3.0 |
| Connection Pool | HikariCP | Managed by Boot parent |
| Web server | Apache Tomcat (embedded) | Managed by Boot parent |
| Authentication | Spring Security 6, JJWT | 0.12.6 |
| OAuth2 | Spring OAuth2 Client + Google OIDC | Managed by Boot parent |
| Cryptography | BouncyCastle (bcprov + bcpkix) | 1.78.1 |
| DTO mapping | MapStruct | 1.6.3 |
| Boilerplate reduction | Lombok | 1.18.36 |
| API documentation | springdoc-openapi | 2.8.4 |
| Messaging | RabbitMQ (Spring AMQP) | 3.12 |
| Containerisation | Docker + Docker Compose | — |
| Build tool | Maven | — |

---

## 3. Code Structure

```
CertManagerCloud/
├── Dockerfile                              # Runtime-only image (JAR pre-built on host)
├── docker-compose.yml                      # Full-stack orchestration (5 services)
├── pom.xml                                 # Maven build descriptor
├── ARCHITECTURE.md                         # This document
│
├── postgres/init/                          # PostgreSQL init scripts
├── rabbitmq/                               # RabbitMQ config
├── monitoring/prometheus.yml               # Prometheus scrape config
├── certs/                                  # TLS keystores (gitignored)
│
└── src/main/
    ├── java/com/codecatalyst/
    │   ├── CertGuardApplication.java       # @SpringBootApplication + @EnableScheduling
    │   │
    │   ├── entity/                         # JPA-mapped domain objects
    │   │   ├── BaseEntity.java             # Abstract superclass: UUID PK, created_at, updated_at
    │   │   ├── Organization.java
    │   │   ├── User.java
    │   │   ├── Target.java
    │   │   ├── CertificateRecord.java
    │   │   ├── Agent.java
    │   │   ├── AgentRegistrationToken.java
    │   │   ├── AgentScanJob.java
    │   │   └── Subscription.java
    │   │
    │   ├── enums/
    │   │   ├── UserRole.java               # ADMIN | MEMBER | VIEWER
    │   │   ├── CertStatus.java             # VALID | EXPIRING | EXPIRED | UNREACHABLE | UNKNOWN
    │   │   ├── AgentStatus.java            # PENDING | ACTIVE | REVOKED | EXPIRED
    │   │   ├── ScanJobStatus.java          # PENDING | CLAIMED | COMPLETED | FAILED
    │   │   ├── HostType.java               # DOMAIN | IP | HOSTNAME
    │   │   └── SubscriptionStatus.java     # ACTIVE | TRIAL | SUSPENDED | CANCELLED
    │   │
    │   ├── repository/                     # Spring Data JPA interfaces
    │   │   ├── OrganizationRepository.java
    │   │   ├── UserRepository.java
    │   │   ├── TargetRepository.java
    │   │   ├── CertificateRecordRepository.java
    │   │   ├── AgentRepository.java
    │   │   ├── AgentRegistrationTokenRepository.java
    │   │   ├── AgentScanJobRepository.java
    │   │   └── SubscriptionRepository.java
    │   │
    │   ├── service/                        # Business logic
    │   │   ├── OrgService.java
    │   │   ├── TargetService.java
    │   │   ├── CertificateService.java
    │   │   ├── AgentService.java
    │   │   ├── SslScannerService.java
    │   │   └── OAuth2UserService.java
    │   │
    │   ├── controller/                     # REST endpoints
    │   │   ├── OrgController.java
    │   │   ├── TargetController.java
    │   │   ├── CertificateController.java
    │   │   ├── AgentController.java
    │   │   ├── AgentDownloadController.java
    │   │   ├── DevAuthController.java      # Dev-mode only; disabled when app.dev-mode=false
    │   │   └── SpaController.java          # Catch-all for React SPA routing
    │   │
    │   ├── security/
    │   │   ├── JwtTokenProvider.java       # JJWT token generation & validation
    │   │   ├── JwtAuthenticationFilter.java # Bearer token filter (OncePerRequestFilter)
    │   │   ├── AgentAuthFilter.java        # X-Agent-Key / X-Agent-Id header filter
    │   │   ├── CertGuardUserPrincipal.java # Custom UserDetails + OidcUser
    │   │   ├── TenantContext.java          # ThreadLocal org/user ID isolation
    │   │   ├── AgentCertificateAuthority.java # BouncyCastle CA for agent mTLS certs
    │   │   └── AgentHmacService.java       # HMAC signing for agent requests
    │   │
    │   ├── config/
    │   │   ├── SecurityConfig.java         # HTTP security, filter chain, CORS, OAuth2
    │   │   ├── FlywayConfig.java           # Programmatic Flyway (spring.flyway.enabled=false)
    │   │   ├── PasswordEncoderConfig.java  # BCryptPasswordEncoder bean
    │   │   ├── OAuth2AuthenticationSuccessHandler.java
    │   │   ├── JsonListConverter.java      # Hibernate converter for JSONB list columns
    │   │   └── WebMvcConfig.java
    │   │
    │   ├── dto/
    │   │   ├── request/                    # Incoming JSON bodies
    │   │   └── response/                   # Outgoing JSON
    │   │
    │   ├── exception/
    │   │   ├── GlobalExceptionHandler.java # @RestControllerAdvice → ProblemDetail
    │   │   ├── ResourceNotFoundException.java
    │   │   └── QuotaExceededException.java
    │   │
    │   └── util/
    │       └── HostTypeDetector.java
    │
    └── resources/
        ├── application.yml
        ├── log4j2.xml
        ├── static/                         # Bundled React SPA (served by SpaController)
        └── db/migration/
            ├── V1__core_schema.sql         # organizations, users, subscriptions, targets, certificate_records
            ├── V2__target_enhancements.sql # targets.tags JSONB column
            └── V3__agent_schema.sql        # agents, agent_registration_tokens, agent_scan_jobs; adds agent_id to targets
```

---

## 4. Application Layers

The application follows a strict three-layer architecture. Each layer has a single responsibility and may only call the layer directly beneath it.

```
  HTTP Request
       │
       ▼
┌─────────────┐
│  Controller │  Reads path/query params + request body; calls service; returns HTTP status
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Service   │  Owns all business logic, @Transactional boundaries, validation, exceptions
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Repository  │  Spring Data JPA interfaces — all DB access goes here
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  PostgreSQL │  Persistent store
└─────────────┘
```

### Controller Layer

Controllers are `@RestController` classes. They read HTTP inputs (path variables, query params, request bodies, request attributes set by filters), delegate entirely to a service, and return an appropriate HTTP status. They contain no business logic and no try/catch blocks — exceptions propagate to `GlobalExceptionHandler`.

The current org is always obtained from `TenantContext.getOrgId()` (set by the JWT filter), not from a URL path segment. This means API consumers are always scoped to their own organisation.

### Service Layer

Services are `@Service` classes annotated with `@Transactional(readOnly = true)` at the class level. Write methods override with `@Transactional`. Services validate business rules (duplicate detection, quota enforcement), resolve cross-entity relationships, and throw typed exceptions for error cases. Read-only transactions let Hibernate skip dirty-checking and allow future read-replica routing.

### Repository Layer

Repositories extend `JpaRepository<Entity, UUID>`. Custom query methods use Spring Data's derived-query naming convention for simple lookups and `@Query` with JPQL for complex predicates (e.g., expiry range queries, paginated listing with org filtering).

---

## 5. Database Design

Schema is owned exclusively by Flyway migration scripts in `src/main/resources/db/migration/`. Hibernate is set to `ddl-auto: none` and Flyway's Spring Boot auto-configuration is disabled (`spring.flyway.enabled: false`); migrations run programmatically via `FlywayConfig` on startup.

### Entity Relationship Diagram

```
┌─────────────────────────────────┐
│         organizations           │
│─────────────────────────────────│
│ id (UUID, PK)                   │
│ name (VARCHAR, NOT NULL)        │
│ slug (VARCHAR, UNIQUE)          │
│ created_at, updated_at          │
└──────────────┬──────────────────┘
               │ 1
       ┌───────┴────────────────────────────────┐
       │               │               │        │
       ▼ *             ▼ 1             ▼ *      ▼ *
┌───────────┐  ┌──────────────┐  ┌─────────┐  ┌────────┐
│   users   │  │ subscriptions│  │ targets │  │ agents │
│───────────│  │──────────────│  │─────────│  │────────│
│ id (UUID) │  │ id (UUID)    │  │ id      │  │ id     │
│ org_id FK │  │ org_id FK    │  │ org_id  │  │ org_id │
│ email     │  │ max_targets  │  │ host    │  │ name   │
│ name      │  │   (default   │  │ port    │  │ agent_ │
│ role ENUM │  │    10)       │  │host_type│  │ key_   │
│ google_sub│  │ status ENUM  │  │is_priv  │  │  hash  │
└───────────┘  └──────────────┘  │ desc    │  │ status │
                                 │ enabled │  │ max_   │
                                 │agent_id◄├──┤targets │
                                 │tags JSONB  │ allowed│
                                 │last_scanned│ _cidrs │
                                 └─────┬───┘  └───┬────┘
                                       │ 1         │ 1
                                       │           │
                              ┌────────┴────┐  ┌───┴──────────────────┐
                              │ certificate │  │ agent_scan_jobs      │
                              │  _records   │  │──────────────────────│
                              │─────────────│  │ id, agent_id, target │
                              │ id          │  │ _id, org_id, status  │
                              │ target_id FK│  │ claimed_at,          │
                              │ org_id      │  │ completed_at         │
                              │ common_name │  └──────────────────────┘
                              │ issuer      │
                              │ serial_no   │  ┌──────────────────────────┐
                              │ expiry_date │  │ agent_registration_tokens│
                              │ not_before  │  │──────────────────────────│
                              │ public_cert │  │ id, org_id, token_hash   │
                              │ status ENUM │  │ agent_name, used, expiry │
                              │ key_algo    │  │ created_by (FK users)    │
                              │ key_size    │  └──────────────────────────┘
                              │ sig_algo    │
                              │ san (JSONB) │
                              │ chain_depth │
                              │ scanned_by_ │
                              │  agent_id   │
                              └─────────────┘
```

### Table Descriptions

**organizations** is the root tenant entity. Every other table has a `org_id` foreign key back to it. The `slug` field is a URL-safe unique identifier. Deleting an organisation cascades to all child records.

**users** stores platform users, each belonging to exactly one organisation. The `role` column is a PostgreSQL ENUM (`ADMIN` | `MEMBER` | `VIEWER`). The `google_sub` column stores the Google OIDC subject identifier for OAuth2-authenticated users.

**subscriptions** enforces per-organisation quotas. There is exactly one subscription per organisation (enforced by `UNIQUE(org_id)`). `max_targets` controls how many monitored hosts the org can register; `QuotaExceededException` is thrown when this limit is reached.

**targets** represents a host/port combination to monitor. `host_type` (DOMAIN | IP | HOSTNAME) is detected automatically from the host string. `is_private` indicates the target sits on a private network reachable only via an agent. `agent_id` (nullable) links the target to the agent responsible for scanning it. The `tags` JSONB column (added in V2) holds arbitrary key-value labels. `enabled` allows targets to be paused without deletion.

**certificate_records** stores scan results. It holds `org_id` directly (denormalised from the target) to allow efficient org-wide certificate queries without a JOIN. The V3 migration added cryptographic metadata columns: `key_algorithm`, `key_size`, `signature_algorithm`, `subject_alt_names` (JSONB array), `chain_depth`, and `scanned_by_agent_id`.

**agents** represents registered distributed scanning agents. `agent_key_hash` is the BCrypt hash of the agent's API key (plaintext is shown once at registration, never stored). `allowed_cidrs` (JSONB) restricts which network ranges the agent is permitted to scan. `client_cert_pem` and `client_cert_fingerprint` support mTLS via the `AgentCertificateAuthority`.

**agent_registration_tokens** are single-use tokens (valid 1 hour) that authorise an agent to self-register. `token_hash` is the BCrypt hash; the plaintext is shown to the admin once.

**agent_scan_jobs** tracks individual scan assignments from server to agent. Status transitions: `PENDING` → `CLAIMED` → `COMPLETED` | `FAILED`. `org_id` is denormalised for query performance.

### Indexes

| Table | Index | Purpose |
|---|---|---|
| `users` | `idx_users_org_id` | All users per org |
| `users` | `idx_users_email` | Email uniqueness lookup |
| `targets` | `idx_targets_org_id` | All targets per org |
| `targets` | `idx_targets_agent_id` | Targets assigned to an agent |
| `certificate_records` | `idx_certs_target_id` | Certs per target |
| `certificate_records` | `idx_certs_org_id` | Certs per org (denormalised) |
| `certificate_records` | `idx_certs_expiry` | Expiry alerting range queries |
| `certificate_records` | `idx_certs_status` | Status-based filtering |
| `agents` | `idx_agents_org_id` | Agents per org |
| `agents` | `idx_agents_status` | Active agent lookup |
| `agent_registration_tokens` | `idx_reg_tokens_org_id` | Tokens per org |
| `agent_scan_jobs` | `idx_scan_jobs_agent_id` | Jobs for an agent |
| `agent_scan_jobs` | `idx_scan_jobs_target_id` | Jobs for a target |
| `agent_scan_jobs` | `idx_scan_jobs_status` | Pending job polling |

### Timestamps and the `updated_at` Trigger

Every table carries `created_at` and `updated_at` (`TIMESTAMPTZ`). These are managed at two levels for defence in depth. At the application layer, Hibernate's `@CreationTimestamp` and `@UpdateTimestamp` on `BaseEntity` set them on every insert and update. At the database layer, a `BEFORE UPDATE` trigger on every table independently sets `updated_at = NOW()`. Timestamps stay accurate even when rows are modified by tooling that bypasses the application.

### PostgreSQL ENUM Types

All status and role columns use native PostgreSQL ENUMs rather than VARCHAR columns. JPA maps them using `@Enumerated(EnumType.STRING)` with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` and a `columnDefinition` matching the SQL type name. This prevents invalid values from ever reaching the database.

---

## 6. Spring JPA & Hibernate Configuration

Configuration lives in `src/main/resources/application.yml`. All sensitive values are externalised as environment variables with safe local defaults.

### DataSource

```yaml
spring:
  datasource:
    url:      ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/certguard}
    username: ${SPRING_DATASOURCE_USERNAME:certguard_user}
    password: ${SPRING_DATASOURCE_PASSWORD:certguard_pass}
```

### HikariCP Connection Pool

```yaml
    hikari:
      maximum-pool-size: 20
      minimum-idle:       5
      connection-timeout: 30000   # 30 seconds
```

A maximum of 20 connections with a minimum idle of 5 balances throughput against database connection limits. The 30-second connection timeout surfaces availability problems quickly rather than queuing requests indefinitely.

### Hibernate / JPA

```yaml
  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        default_schema: public
```

`ddl-auto: none` means Hibernate neither validates nor alters the schema. All schema management belongs to Flyway. `show-sql: false` keeps logs clean in production; set to `true` only when debugging query behaviour locally.

### Flyway

Spring Boot's auto-configuration for Flyway is disabled (`spring.flyway.enabled: false`). Flyway is instead configured and run programmatically by `FlywayConfig` on startup. This gives explicit control over the migration lifecycle and allows `validateOnMigrate: false` to be set for environments where the schema may have manual modifications.

### Entity Mapping Highlights

**BaseEntity** is an abstract `@MappedSuperclass` that all entities extend. It provides `id` (UUID, `GenerationType.UUID`), `createdAt`, and `updatedAt` managed by Hibernate annotations.

**Relationships** always use `FetchType.LAZY` on `@ManyToOne` associations. JPA's default for `@ManyToOne` is eager, which would silently issue extra SELECT statements for every parent entity when a child is loaded. Explicit lazy loading gives the service layer control over when parent data is fetched.

**Cascade** is declared on the parent side with `CascadeType.ALL` and `orphanRemoval = true` where appropriate (e.g., `Target.certificates`). Deleting a target cascades to its certificate records.

**JSONB columns** (`tags`, `allowed_cidrs`, `subject_alt_names`) use `@JdbcTypeCode(SqlTypes.JSON)` to map to `List<String>` in Java, with PostgreSQL's native JSONB type in the schema.

---

## 7. Security & Authentication

### Overview

The application uses two independent authentication mechanisms in the same filter chain:

```
Incoming Request
      │
      ▼
AgentAuthFilter         (checks X-Agent-Key + X-Agent-Id headers)
      │
      ▼
JwtAuthenticationFilter (checks Authorization: Bearer <token> header)
      │
      ▼
Spring Security built-in filters
```

Both filters run before `UsernamePasswordAuthenticationFilter`. A request is handled by whichever filter matches its auth scheme; the other filter simply passes it through.

### JWT Authentication (user/admin access)

`JwtTokenProvider` uses JJWT 0.12.6 with HMAC-SHA256. Tokens carry `sub` (userId), `orgId`, `email`, `role`, `iat`, and `exp` (24-hour default). The secret is set via `JWT_SECRET` (min 64 chars); the application uses a development default that must be overridden in production.

`JwtAuthenticationFilter` (`OncePerRequestFilter`) extracts the token from the `Authorization: Bearer` header, validates signature and expiry, creates a `CertGuardUserPrincipal`, sets the Spring `SecurityContext`, and calls `TenantContext.setOrgId()`. `TenantContext.clear()` is called in a `finally` block to prevent ThreadLocal leakage between requests.

`CertGuardUserPrincipal` implements both `UserDetails` and `OidcUser`, allowing it to be created from either a JWT or a Google OIDC login. Controllers receive it via `@AuthenticationPrincipal`.

### Agent Authentication (distributed agents)

`AgentAuthFilter` handles the `X-Agent-Key` + `X-Agent-Id` header pair used by deployed agents. For protected agent endpoints, the filter:
1. Looks up the `Agent` entity by `X-Agent-Id`
2. Verifies `status == ACTIVE`
3. BCrypt-compares `X-Agent-Key` against the stored `agentKeyHash`
4. Updates `last_seen_at`
5. Sets `request.setAttribute("authenticatedAgent", agent)` and `request.setAttribute("authenticatedAgentKey", key)` for use in controllers

Agent registration uses a different mechanism: the agent presents a single-use `CGR-<UUID>` registration token via `X-Org-Id` header and request body, which is hash-verified against `agent_registration_tokens`. On success, a one-time `AGK-<UUID><UUID>` key is returned; only its BCrypt hash is stored.

### OAuth2 (production only)

When `app.dev-mode=false`, Google OIDC login is enabled. `OAuth2UserService` resolves the authenticated Google user to a local `User` entity (creating one if first login) and issues a CertGuard JWT via `OAuth2AuthenticationSuccessHandler`. In dev mode, `DevAuthController` provides a `/api/v1/dev/login` endpoint that issues a JWT directly without OAuth2.

### Multi-Tenancy Isolation

`TenantContext` stores `orgId` and `userId` in `ThreadLocal` variables. The JWT filter populates these on every request; services read `TenantContext.getOrgId()` to scope all queries. This ensures data cannot leak across organisations in concurrent requests.

### Security Configuration

```
permitAll:    /api/v1/agent/ca-cert, /api/v1/agent/register,
              /agent/download, /agent/version, / (SPA + static assets)

authenticated: /api/v1/agent/**, /api/v1/dashboard,
               /api/v1/targets/**, /api/v1/certificates/**,
               /api/v1/org/**
```

Sessions are stateless (`SessionCreationPolicy.STATELESS`). CSRF is disabled (no cookies, stateless API). CORS allows all origin patterns with credentials.

### Agent Certificate Authority (mTLS)

`AgentCertificateAuthority` uses BouncyCastle (bcprov + bcpkix) to maintain a self-signed CA. The CA's public certificate is served at `/api/v1/agent/ca-cert` for agents to trust. Agent client certificates (valid 365 days, configurable) are issued at registration for mTLS use.

---

## 8. Docker & Container Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      certguard-net (bridge)                 │
│                                                             │
│  ┌──────────────┐    ┌────────────┐    ┌─────────────────┐  │
│  │  certguard-  │    │ certguard- │    │ certguard-      │  │
│  │    app       │───►│  postgres  │    │  rabbitmq       │  │
│  │  :8443(HTTPS)│    │  :5432     │    │  :5672 / :15672 │  │
│  └──────┬───────┘    └─────┬──────┘    └─────────────────┘  │
│         │                 │                                 │
│  ┌──────▼───────┐  ┌──────▼──────────┐                     │
│  │  certguard-  │  │   postgres_data  │                     │
│  │  prometheus  │  │   (named volume) │                     │
│  │  :9090       │  └─────────────────┘                     │
│  └──────┬───────┘                                          │
│         │                                                  │
│  ┌──────▼───────┐                                          │
│  │  certguard-  │                                          │
│  │   grafana    │                                          │
│  │  :3000       │                                          │
│  └──────────────┘                                          │
└─────────────────────────────────────────────────────────────┘
```

### Services

| Service | Image | Host Port | Purpose |
|---|---|---|---|
| `app` | Built from Dockerfile | `58244→8443` | Spring Boot application (HTTPS) |
| `postgres` | `postgres:15-alpine` | `127.0.0.1:5432` | PostgreSQL database |
| `rabbitmq` | `rabbitmq:3.12-management-alpine` | `127.0.0.1:5672` / `127.0.0.1:15672` | Message broker + management UI |
| `prometheus` | `prom/prometheus:latest` | `127.0.0.1:9090` | Metrics collection |
| `grafana` | `grafana/grafana:latest` | `127.0.0.1:3000` | Dashboards |

All database and infrastructure ports are bound to `127.0.0.1` only — they are not exposed to the network. Only the application port (`58244`) is reachable externally.

### Dockerfile

The Dockerfile is a **single-stage runtime image** — the JAR is built on the host before `docker compose build`:

```bash
mvn clean package -DskipTests   # Build JAR first
docker compose build             # Then build image
docker compose up -d
```

The runtime base image is `eclipse-temurin:17-jre-alpine` (JRE only, no compiler or development tools). A dedicated non-root `certguard` user runs the process. The TLS keystore is volume-mounted from `./certs` into `/opt/certguard/certs` at runtime.

### Startup Ordering

The `app` service declares `depends_on` with `condition: service_healthy` for both `postgres` and `rabbitmq`. Docker Compose will not start the Spring Boot container until both pass their health checks (`pg_isready` for Postgres, `rabbitmq-diagnostics ping` for RabbitMQ). The app health check uses `wget` against `https://localhost:8443/actuator/health` with a 60-second start period.

### Named Volumes

`postgres_data`, `rabbitmq_data`, `prometheus_data`, `grafana_data`, and `app_keystores` are all named volumes that persist across container restarts. Run `docker compose down -v` to wipe all data for a clean start.

---

## 9. REST API Design

All routes are versioned under `/api/v1/`. The authenticated user's organisation is determined from the JWT (via `TenantContext`), not from a URL path variable. This means there is no `/api/v1/org/{orgId}/...` nesting — all data is implicitly scoped to the caller's org.

The application also serves a bundled React SPA: `SpaController` catches all non-API, non-asset requests and returns `index.html` to support client-side routing.

### Endpoint Map

```
/api/v1/

├── org/
│   GET  /                       Get current org details
│   PUT  /name?name=...          Update org name

├── targets/
│   GET  /                       List targets (paginated)
│   POST /                       Create target
│   PUT  /{id}                   Update target
│   DELETE /{id}                 Delete target
│   POST /{id}/scan              Trigger immediate scan
│   GET  /{id}/scan-status       Poll latest scan job status

├── certificates/
│   GET  /                       List certificates (paginated)
│   GET  /expiring?days=N        Certificates expiring within N days (default 30)

├── dashboard/
│   GET  /                       Aggregate stats for org dashboard

└── agent/
    GET  /ca-cert                Agent CA public certificate (no auth)
    POST /register               Agent self-registration (X-Org-Id header + token body)
    POST /tokens?agentName=...   Generate registration token (JWT auth)
    GET  /config?agentName=&token= Download pre-filled agent application.properties (JWT auth)
    GET  /list                   List agents in org (JWT auth)
    POST /{agentId}/revoke       Revoke an agent (JWT auth)
    POST /heartbeat              Agent heartbeat (X-Agent-Key auth)
    GET  /jobs                   Agent polls pending scan jobs (X-Agent-Key auth)
    POST /results                Agent submits scan result (X-Agent-Key auth)

/agent/
    GET  /download               Agent JAR download (no auth)
    GET  /version                Latest agent version info (no auth)
```

### HTTP Status Codes

| Status | Usage |
|---|---|
| `200 OK` | Successful read or action |
| `201 Created` | Successful POST that creates a resource |
| `204 No Content` | Successful DELETE |
| `400 Bad Request` | Validation failure or business rule violation |
| `401 Unauthorized` | Missing or invalid authentication |
| `403 Forbidden` | Authenticated but insufficient permissions |
| `404 Not Found` | Resource does not exist |
| `409 Conflict` | Invalid state transition |
| `429 Too Many Requests` | Subscription quota exceeded |
| `500 Internal Server Error` | Unhandled exception |

### Response Format

Success responses return the entity or DTO directly as JSON. Error responses use RFC 9457 `ProblemDetail`:

```json
{
  "type":   "about:blank",
  "title":  "Not Found",
  "status": 404,
  "detail": "Agent not found: 3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

### DTO Mapping

MapStruct 1.6.3 is used for all entity ↔ DTO conversions. Mappers are `@Mapper(componentModel = "spring")` interfaces whose implementations are generated at compile time by the Maven annotation processor. Request DTOs live in `dto/request/`, response DTOs in `dto/response/`.

---

## 10. Error Handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) centralises all exception-to-HTTP mapping. No controller contains try/catch logic — exceptions propagate upward and are caught here.

| Exception | HTTP Status | Thrown when |
|---|---|---|
| `ResourceNotFoundException` | 404 | Entity not found by ID |
| `QuotaExceededException` | 429 | Target count exceeds subscription limit |
| `IllegalArgumentException` | 400 | Business rule violated (duplicate, invalid input) |
| `IllegalStateException` | 409 | Invalid state transition (e.g., revoking an already-revoked agent) |
| `SecurityException` | 403 | Auth check failed |
| `NoResourceFoundException` | 404 | Spring MVC route not found |
| `Exception` (catch-all) | 500 | Any unhandled exception (logged at ERROR) |

---

## 11. Key Design Decisions

### Multi-Tenancy via TenantContext, Not URL Parameters

The authenticated user's `orgId` is extracted from the JWT and stored in a `ThreadLocal` (`TenantContext`) for the duration of the request. Controllers read `TenantContext.getOrgId()` instead of accepting `{orgId}` as a URL path variable. This prevents a class of IDOR vulnerabilities where a caller could substitute another org's ID in the URL. The trade-off is that admin operations across multiple orgs require elevated authentication rather than simple URL parameterisation.

### Schema Ownership Belongs to Flyway, Not Hibernate

`ddl-auto: none` means Hibernate never touches the schema. Flyway migration scripts (`V1`, `V2`, `V3`) are the single authoritative source of truth. This makes schema changes explicit, reviewable in code review, and safely applicable in production. It also allows PostgreSQL-specific features (native ENUMs, JSONB, `gen_random_uuid()`, triggers) that Hibernate cannot generate.

### Read-Only Transactions as the Default

`@Transactional(readOnly = true)` at the service class level is the default; write methods override to `@Transactional`. Read-only transactions skip Hibernate's dirty-checking of all loaded entities, reducing memory pressure and CPU overhead on read-heavy paths. They also allow future routing of reads to a PostgreSQL read replica without code changes.

### Dual-Layer Duplicate Protection

Business uniqueness constraints (email, org slug, token hash) are enforced at both the application level (service check → descriptive 400 error) and the database level (unique constraints). The application check gives the API caller a useful error message. The database constraint is the safety net for concurrent requests that both pass the application check simultaneously.

### Denormalised `org_id` on `certificate_records` and `agent_scan_jobs`

Both tables carry `org_id` even though it could be derived via a JOIN through their parent table. The org-wide certificate listing and scan job queries are high-frequency operations. The denormalisation lets these queries use the `idx_certs_org_id` / `idx_scan_jobs_status` indexes without a JOIN. The foreign key constraint on `org_id` in `organizations` ensures the value stays consistent (deletions cascade automatically).

### Agents Use BCrypt for Key Storage, Not Reversible Encryption

Agent API keys are 64-character random strings shown to the operator once at registration. Only the BCrypt hash is stored. This is the same design as password storage: even with full database access, an attacker cannot recover a valid agent key. The trade-off is that lost keys cannot be recovered — the operator must revoke the agent and register a new one.

### Non-Root Container Process

The Spring Boot container runs as `certguard:certguard` (non-root). If a vulnerability were exploited in the application, the attacker's capabilities would be limited to that user's privileges inside the container, rather than having root access to the container filesystem.

### Application Serves Both API and SPA

`SpaController` catches all non-API, non-static-asset requests and returns `index.html`, enabling React Router's client-side routing to work correctly without a separate NGINX process. Static assets are served from `classpath:/static/` with a 1-hour cache header. NGINX was explicitly removed from the stack (noted in `docker-compose.yml`) since Spring Boot handles HTTPS directly.
