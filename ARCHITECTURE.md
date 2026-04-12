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

The platform supports two organisation types: **SINGLE** (standard tenant) and **MSP** (Managed Service Provider), where an MSP org can create and manage child client orgs. Org membership is tracked via `OrgMember` records with fine-grained `OrgMemberRole` permissions. Email notification alerts for certificate expiry are dispatched asynchronously via `NotificationService`.

The service is built on Spring Boot 4.0.3 with Java 17, Spring Data JPA backed by PostgreSQL, Flyway for schema management, and the full stack runs as Docker Compose containers. The application serves both a REST API and a bundled React SPA from the same process.

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
| Email | Spring Mail (JavaMailSender) | Managed by Boot parent |
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
    │   ├── CertGuardApplication.java       # @SpringBootApplication + @EnableScheduling + @EnableAsync
    │   │
    │   ├── entity/                         # JPA-mapped domain objects
    │   │   ├── BaseEntity.java             # Abstract superclass: UUID PK, created_at, updated_at
    │   │   ├── Organization.java           # Root tenant; supports SINGLE and MSP types + contact profile
    │   │   ├── User.java                   # Platform user; belongs to one org; holds global UserRole
    │   │   ├── Target.java                 # Host:port to monitor; links to Location + notification config
    │   │   ├── CertificateRecord.java      # Scan result snapshot per target
    │   │   ├── Subscription.java           # Per-org quota (max_certificate_quota)
    │   │   ├── Agent.java                  # Registered distributed scanning agent
    │   │   ├── AgentRegistrationToken.java # Single-use agent registration token
    │   │   ├── AgentScanJob.java           # Individual scan job assigned to an agent
    │   │   ├── OrgMember.java              # Org membership record (org↔user with OrgMemberRole)
    │   │   ├── OrgInvitation.java          # Invitation to join org (stores UserRole; legacy path)
    │   │   ├── Invitation.java             # Invitation via hashed token (stores OrgMemberRole; new path)
    │   │   └── Location.java               # Network location label for grouping targets
    │   │
    │   ├── enums/
    │   │   ├── UserRole.java               # PLATFORM_ADMIN | SUPER_ADMIN | ADMIN | MEMBER | VIEWER
    │   │   ├── OrgType.java                # SINGLE | MSP
    │   │   ├── OrgMemberRole.java          # ADMIN | ENGINEER | VIEWER (org-level membership role)
    │   │   ├── InviteStatus.java           # PENDING | ACCEPTED | REVOKED
    │   │   ├── LocationProvider.java       # AWS | AZURE | GCP | COLOCATION | ON_PREM
    │   │   ├── CertStatus.java             # VALID | EXPIRING | EXPIRED | UNREACHABLE | UNKNOWN
    │   │   ├── AgentStatus.java            # PENDING | ACTIVE | REVOKED | EXPIRED
    │   │   ├── ScanJobStatus.java          # PENDING | CLAIMED | COMPLETED | FAILED
    │   │   ├── HostType.java               # DOMAIN | IP | HOSTNAME
    │   │   └── SubscriptionStatus.java     # ACTIVE | TRIAL | SUSPENDED | CANCELLED
    │   │
    │   ├── repository/                     # Spring Data JPA interfaces
    │   │   ├── OrganizationRepository.java
    │   │   ├── UserRepository.java         # Includes org-scoped lookups (findAllByOrganizationId, etc.)
    │   │   ├── TargetRepository.java
    │   │   ├── CertificateRecordRepository.java
    │   │   ├── AgentRepository.java
    │   │   ├── AgentRegistrationTokenRepository.java
    │   │   ├── AgentScanJobRepository.java
    │   │   ├── SubscriptionRepository.java
    │   │   ├── OrgMemberRepository.java    # findAllByOrganizationId, findByOrganizationIdAndUserId
    │   │   ├── OrgInvitationRepository.java # Legacy invitation queries
    │   │   ├── InvitationRepository.java   # New token-hash-based invitation queries
    │   │   └── LocationRepository.java
    │   │
    │   ├── service/                        # Business logic
    │   │   ├── OrgService.java             # Org profile read/update; legacy name update
    │   │   ├── TargetService.java          # CRUD, quota enforcement, notification channel update
    │   │   ├── CertificateService.java
    │   │   ├── AgentService.java
    │   │   ├── SslScannerService.java      # Direct TLS connection scan (non-agent path)
    │   │   ├── OAuth2UserService.java      # Google OIDC → local User resolution
    │   │   ├── TeamService.java            # OrgMember management + new Invitation flow
    │   │   ├── InvitationService.java      # Invite email dispatch + OTP acceptance
    │   │   ├── LocationService.java        # CRUD for Location entities
    │   │   ├── MspClientService.java       # MSP creates/manages child client orgs
    │   │   ├── NotificationService.java    # @Async certificate-expiry email alerts
    │   │   ├── AdminService.java           # SUPER_ADMIN: list all orgs, update quotas
    │   │   └── OrgMemberService.java       # Legacy member management via OrgInvitation
    │   │
    │   ├── controller/                     # REST endpoints (no business logic, no try/catch)
    │   │   ├── OrgController.java          # /api/v1/org — profile, name, PLATFORM_ADMIN quota ops
    │   │   ├── TargetController.java       # /api/v1/targets — CRUD, scan trigger, notifications
    │   │   ├── CertificateController.java  # /api/v1/certificates + /api/v1/dashboard
    │   │   ├── AgentController.java        # /api/v1/agent — registration, jobs, results
    │   │   ├── AgentDownloadController.java # /agent/download, /agent/version (no auth)
    │   │   ├── AdminController.java        # /api/v1/admin — SUPER_ADMIN subscription management
    │   │   ├── TeamController.java         # /api/v1/org/members + /api/v1/org/invitations
    │   │   ├── LocationController.java     # /api/v1/locations — CRUD
    │   │   ├── MspClientController.java    # /api/v1/msp/clients — MSP client org management
    │   │   ├── DevAuthController.java      # Dev-mode only; disabled when app.dev-mode=false
    │   │   └── SpaController.java          # Catch-all → index.html for React Router
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
    │   │   ├── request/                    # Incoming JSON bodies (AcceptInviteRequest,
    │   │   │                               #   CreateTargetRequest, InviteMemberRequest,
    │   │   │                               #   CreateLocationRequest, CreateClientOrgRequest,
    │   │   │                               #   UpdateOrgProfileRequest, UpdateQuotaRequest, ...)
    │   │   └── response/                   # Outgoing JSON (OrgResponse, TargetResponse,
    │   │                                   #   CertificateResponse, InvitationResponse,
    │   │                                   #   OrgMemberResponse, LocationResponse,
    │   │                                   #   SubscriptionResponse, MemberResponse, ...)
    │   │
    │   ├── exception/
    │   │   ├── GlobalExceptionHandler.java # @RestControllerAdvice → ProblemDetail
    │   │   ├── ResourceNotFoundException.java
    │   │   └── QuotaExceededException.java
    │   │
    │   ├── net/
    │   │   └── FetchCertificates.java      # Low-level TLS certificate fetching utility
    │   │
    │   └── util/
    │       └── HostTypeDetector.java       # Classifies host strings as DOMAIN / IP / HOSTNAME
    │
    └── resources/
        ├── application.yml
        ├── static/                         # Bundled React SPA (served by SpaController)
        └── db/migration/
            ├── V1__core_schema.sql         # organizations, users, subscriptions, targets, certificate_records
            ├── V2__target_enhancements.sql # targets.tags JSONB column
            ├── V3__agent_schema.sql        # agents, agent_registration_tokens, agent_scan_jobs
            ├── V4__org_invitations.sql     # org_invitations table (legacy invitation path)
            └── V5__super_admin_role.sql    # Adds SUPER_ADMIN to user_role PostgreSQL enum
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

The current org is always obtained from `TenantContext.getOrgId()` (set by the JWT filter), not from a URL path segment. Role-based access control is enforced at the method level with `@PreAuthorize` annotations (e.g., `hasRole('PLATFORM_ADMIN')`, `hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')`). `@EnableMethodSecurity` is active on `SecurityConfig`.

### Service Layer

Services are `@Service` classes annotated with `@Transactional(readOnly = true)` at the class level. Write methods override with `@Transactional`. Services validate business rules (duplicate detection, quota enforcement), resolve cross-entity relationships, and throw typed exceptions for error cases. `NotificationService` uses `@Async` for fire-and-forget email dispatch so that expiry alerts do not block the scan write path.

### Repository Layer

Repositories extend `JpaRepository<Entity, UUID>`. Custom query methods use Spring Data's derived-query naming convention for simple lookups and `@Query` with JPQL for complex predicates (e.g., expiry range queries, paginated listing with org filtering).

---

## 5. Database Design

Schema is owned exclusively by Flyway migration scripts in `src/main/resources/db/migration/`. Hibernate is set to `ddl-auto: none` and Flyway's Spring Boot auto-configuration is disabled (`spring.flyway.enabled: false`); migrations run programmatically via `FlywayConfig` on startup.

### Entity Relationship Diagram

```
┌──────────────────────────────────────────────┐
│                  organizations               │
│──────────────────────────────────────────────│
│ id (UUID, PK)                                │
│ name, slug                                   │
│ org_type (SINGLE|MSP)                        │
│ parent_org_id FK → organizations (nullable)  │
│ address_line1/2, city, state_province        │
│ postal_code, country, phone, contact_email   │
│ created_at, updated_at                       │
└──────────────────┬───────────────────────────┘
                   │ 1
     ┌─────────────┼──────────────────────────────┐
     │             │              │               │
     ▼ *           ▼ 1            ▼ *             ▼ *
┌──────────┐ ┌──────────────┐ ┌─────────┐  ┌──────────┐
│  users   │ │ subscriptions│ │ targets │  │  agents  │
│──────────│ │──────────────│ │─────────│  │──────────│
│ id       │ │ id           │ │ id      │  │ id       │
│ org_id FK│ │ org_id FK    │ │ org_id  │  │ org_id   │
│ email    │ │ max_cert_    │ │ host    │  │ name     │
│ name     │ │  quota       │ │ port    │  │ key_hash │
│ role ENUM│ │  (default 10)│ │host_type│  │ status   │
│google_sub│ │ status ENUM  │ │is_priv  │  │ allowed_ │
└────┬─────┘ └──────────────┘ │desc     │  │  cidrs   │
     │                        │enabled  │  └────┬─────┘
     │ *                      │agent_id │       │ 1
     ▼                        │location │  ┌────┴───────────┐
┌──────────────┐              │  _id FK │  │ agent_scan_jobs│
│  org_members │              │tags JSONB  │────────────────│
│──────────────│              │notif_ch │  │ id, org_id     │
│ id           │              │  _JSONB │  │ agent_id       │
│ org_id FK    │              └────┬────┘  │ target_id      │
│ user_id FK   │                   │ 1     │ status ENUM    │
│ role ENUM    │                   │       └────────────────┘
│ invited_by FK│          ┌────────┴──────┐
│ invite_status│          │cert_records   │
└──────────────┘          │───────────────│
                          │ id            │
┌──────────────┐          │ target_id FK  │
│  invitations │          │ org_id        │
│──────────────│          │ common_name   │
│ id           │          │ issuer        │
│ org_id FK    │          │ serial_no     │
│ email        │          │ expiry_date   │
│ role ENUM    │          │ not_before    │
│  (OrgMember  │          │ public_cert   │
│   Role)      │          │ status ENUM   │
│ token_hash   │          │ key_algo      │
│ invited_by FK│          │ key_size      │
│ expires_at   │          │ sig_algo      │
│ used_at      │          │ san (JSONB)   │
└──────────────┘          │ chain_depth   │
                          │ scanned_by_   │
┌──────────────────┐      │  agent_id     │
│  org_invitations │      └───────────────┘
│──────────────────│
│ id               │  ┌─────────────────────────┐
│ org_id FK        │  │ agent_registration_tokens│
│ email            │  │─────────────────────────│
│ role (UserRole)  │  │ id, org_id, token_hash   │
│ token            │  │ agent_name, used, expiry │
│ used (bool)      │  │ created_by FK → users    │
│ invited_by FK    │  └─────────────────────────┘
│ expires_at       │
└──────────────────┘  ┌──────────────┐
                      │  locations   │
                      │──────────────│
                      │ id           │
                      │ org_id FK    │
                      │ name         │
                      │ provider ENUM│
                      │ geo_region   │
                      │ cloud_region │
                      │ address      │
                      │custom_fields │
                      │  (JSONB)     │
                      └──────────────┘
```

### Table Descriptions

**organizations** is the root tenant entity. Every other table has an `org_id` foreign key back to it. The `slug` field is a URL-safe unique identifier. `org_type` is `SINGLE` (standard tenant) or `MSP` (Managed Service Provider). MSP orgs reference child client orgs via `parent_org_id`. Address/contact fields were added for org profile management.

**users** stores platform users, each belonging to exactly one organisation. The `role` column is a PostgreSQL ENUM with five values: `PLATFORM_ADMIN` (cross-org operator), `SUPER_ADMIN` (elevated admin, V5 migration), `ADMIN` (org admin), `MEMBER` (standard), `VIEWER` (read-only). The `google_sub` column stores the Google OIDC subject identifier.

**subscriptions** enforces per-organisation quotas. There is exactly one subscription per organisation. `max_certificate_quota` (renamed from `max_targets` in `latest` V4 migration) controls how many scanned certificates the org is allowed; `QuotaExceededException` (HTTP 429) is thrown when the limit is reached.

**targets** represents a host/port combination to monitor. `host_type` (`DOMAIN | IP | HOSTNAME`) is auto-detected by `HostTypeDetector`. `is_private` flags targets reachable only via an agent. `location_id` (nullable FK) groups the target under a `Location`. `notification_channels` is a JSONB map of channel configs — currently only `email` is live; SMS, WhatsApp, Slack, Teams, PSA, and ServiceDesk are stored and displayed as read-only until implemented.

**certificate_records** stores scan results. `org_id` is denormalised from the target for efficient org-wide queries. Cryptographic metadata columns (`key_algorithm`, `key_size`, `signature_algorithm`, `subject_alt_names` JSONB, `chain_depth`, `scanned_by_agent_id`) were added in V3.

**agents** represents registered distributed scanning agents. `agent_key_hash` is BCrypt. `allowed_cidrs` (JSONB) restricts scan targets. `client_cert_pem` and `client_cert_fingerprint` support mTLS via `AgentCertificateAuthority`.

**agent_registration_tokens** are single-use tokens (1-hour validity) for agent self-registration. `token_hash` is BCrypt.

**agent_scan_jobs** tracks individual scan assignments. Status: `PENDING → CLAIMED → COMPLETED | FAILED`. `org_id` is denormalised.

**org_members** tracks fine-grained org membership. A user can belong to multiple orgs (unlike `users.org_id` which tracks their primary org). `role` is `OrgMemberRole` (`ADMIN | ENGINEER | VIEWER`). `invite_status` is `PENDING | ACCEPTED | REVOKED`.

**invitations** is the new invitation path used by `TeamService`. The invite token is never stored — only its SHA-256 hash (`token_hash`) is persisted. Role is `OrgMemberRole`. `used_at` records acceptance time.

**org_invitations** is the legacy invitation table used by `OrgMemberService`. It stores the raw token (not hashed) and role as `UserRole`. Both invitation paths coexist; new code should use `invitations`.

**locations** groups targets by network location (AWS region, on-prem datacenter, etc.). `custom_fields` (JSONB) holds arbitrary provider-specific metadata.

### Flyway Migrations

| Version | File | What it does |
|---|---|---|
| V1 | `V1__core_schema.sql` | Base tables: organizations, users, subscriptions, targets, certificate_records; PostgreSQL ENUMs: user_role, cert_status, host_type, subscription_status |
| V2 | `V2__target_enhancements.sql` | Adds `tags` JSONB column to targets |
| V3 | `V3__agent_schema.sql` | Adds agents, agent_registration_tokens, agent_scan_jobs; adds agent_id FK to targets |
| V4 | `V4__org_invitations.sql` | Creates org_invitations table (legacy invite path, role stored as user_role) |
| V5 | `V5__super_admin_role.sql` | `ALTER TYPE user_role ADD VALUE 'SUPER_ADMIN'` |

### Indexes

| Table | Index | Purpose |
|---|---|---|
| `users` | `idx_users_org_id` | All users per org |
| `users` | `idx_users_email` | Email lookup |
| `targets` | `idx_targets_org_id` | All targets per org |
| `targets` | `idx_targets_agent_id` | Targets assigned to an agent |
| `certificate_records` | `idx_certs_target_id` | Certs per target |
| `certificate_records` | `idx_certs_org_id` | Org-wide cert queries (denormalised) |
| `certificate_records` | `idx_certs_expiry` | Expiry alerting range queries |
| `certificate_records` | `idx_certs_status` | Status-based filtering |
| `agents` | `idx_agents_org_id` | Agents per org |
| `agents` | `idx_agents_status` | Active agent lookup |
| `agent_registration_tokens` | `idx_reg_tokens_org_id` | Tokens per org |
| `agent_scan_jobs` | `idx_scan_jobs_agent_id` | Jobs for an agent |
| `agent_scan_jobs` | `idx_scan_jobs_target_id` | Jobs for a target |
| `agent_scan_jobs` | `idx_scan_jobs_status` | Pending job polling |
| `org_members` | `idx_org_members_org_id` | Members per org |
| `org_members` | `idx_org_members_user_id` | Orgs a user belongs to |
| `org_invitations` | `idx_invitations_org_id` | Invitations per org |
| `org_invitations` | `idx_invitations_email` | Lookup by email |
| `org_invitations` | `idx_invitations_token` | Token lookup |

### Timestamps and the `updated_at` Trigger

Every table carries `created_at` and `updated_at` (`TIMESTAMPTZ`). These are managed at two levels. At the application layer, Hibernate's `@CreationTimestamp` and `@UpdateTimestamp` on `BaseEntity` set them on every insert/update. At the database layer, a `BEFORE UPDATE` trigger on every table independently sets `updated_at = NOW()`. Timestamps remain accurate even when rows are modified by tooling that bypasses the application.

### PostgreSQL ENUM Types

All status and role columns use native PostgreSQL ENUMs. JPA maps them using `@Enumerated(EnumType.STRING)` with `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` and a `columnDefinition` matching the SQL type name. Current DB ENUMs: `user_role`, `cert_status`, `host_type`, `subscription_status`, `agent_status`, `scan_job_status`, `org_member_role`, `invite_status`, `org_type`, `location_provider`.

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

A maximum of 20 connections with a minimum idle of 5 balances throughput against database connection limits. The 30-second timeout surfaces availability problems quickly rather than queuing requests indefinitely.

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

`ddl-auto: none` means Hibernate neither validates nor alters the schema. All schema management belongs to Flyway. `show-sql: false` keeps logs clean in production.

### Flyway

Spring Boot's Flyway auto-configuration is disabled (`spring.flyway.enabled: false`). Flyway is configured and run programmatically by `FlywayConfig` on startup, giving explicit control over the migration lifecycle.

### Entity Mapping Highlights

**BaseEntity** is an abstract `@MappedSuperclass` providing `id` (UUID, `GenerationType.UUID`), `createdAt`, and `updatedAt` via Hibernate annotations.

**Relationships** always use `FetchType.LAZY` on `@ManyToOne`. JPA's default for `@ManyToOne` is eager, which would silently issue extra SELECT statements. Explicit lazy loading gives services control over when parent data is fetched.

**JSONB columns** (`tags`, `allowed_cidrs`, `subject_alt_names`, `notification_channels`, `custom_fields`) use `@JdbcTypeCode(SqlTypes.JSON)` or `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`. List columns map to `List<String>` or `List<T>`; the notification channel map uses `Map<String, Object>`.

---

## 7. Security & Authentication

### Overview

The application uses two independent authentication mechanisms in the same filter chain, plus method-level RBAC:

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
      │
      ▼
@PreAuthorize on controller methods  (role checks: PLATFORM_ADMIN, SUPER_ADMIN, ADMIN, etc.)
```

Both filters run before `UsernamePasswordAuthenticationFilter`. A request is handled by whichever filter matches; the other passes it through.

### JWT Authentication (user/admin access)

`JwtTokenProvider` uses JJWT 0.12.6 with HMAC-SHA256. Tokens carry `sub` (userId), `orgId`, `email`, `role`, `iat`, and `exp` (24-hour default). The secret is set via `JWT_SECRET` (min 64 chars).

`JwtAuthenticationFilter` (`OncePerRequestFilter`) extracts the token from the `Authorization: Bearer` header, validates signature and expiry, creates a `CertGuardUserPrincipal`, sets the Spring `SecurityContext`, and calls `TenantContext.setOrgId()`. `TenantContext.clear()` runs in a `finally` block to prevent ThreadLocal leakage.

`CertGuardUserPrincipal` implements both `UserDetails` and `OidcUser`, allowing it to be created from either a JWT or a Google OIDC login.

### Role-Based Access Control

Fine-grained access is enforced at the controller method level via `@PreAuthorize`. `@EnableMethodSecurity` activates this in `SecurityConfig`.

| Role | Who | Used by |
|---|---|---|
| `SUPER_ADMIN` | Elevated platform operator | `AdminController` (all endpoints) |
| `PLATFORM_ADMIN` | Standard platform operator | `OrgController` admin sub-routes; `MspClientController` |
| `ADMIN` | Org-level admin | Team invitations, role changes, member removal |
| `ENGINEER` | Technical org member | Read access to MSP client list |
| `MEMBER` / `VIEWER` | Standard members | Implicitly granted by `authenticated()` catch-all |

### Agent Authentication (distributed agents)

`AgentAuthFilter` handles the `X-Agent-Key` + `X-Agent-Id` header pair. For protected agent endpoints it:
1. Looks up the `Agent` entity by `X-Agent-Id`
2. Verifies `status == ACTIVE`
3. BCrypt-compares `X-Agent-Key` against the stored `agentKeyHash`
4. Updates `last_seen_at`
5. Sets `request.setAttribute("authenticatedAgent", agent)` for controller use

Agent registration uses a single-use `CGR-<UUID>` token via `X-Org-Id` header. On success, a one-time `AGK-<UUID><UUID>` key is returned; only its BCrypt hash is stored.

### OAuth2 (production only)

When `app.dev-mode=false`, Google OIDC login is enabled. `OAuth2UserService` resolves the authenticated Google user to a local `User` entity (creating one on first login) and issues a CertGuard JWT via `OAuth2AuthenticationSuccessHandler`. In dev mode, `DevAuthController` provides a `/api/v1/auth/dev-token` endpoint that issues a JWT directly.

### Multi-Tenancy Isolation

`TenantContext` stores `orgId` and `userId` in `ThreadLocal` variables. The JWT filter populates these on every request; services read `TenantContext.getOrgId()` to scope all queries, preventing data from leaking across organisations in concurrent requests.

### Security Configuration — URL Rules

```
permitAll:
  /api/v1/agent/ca-cert           Agent CA cert download
  /api/v1/agent/register          Agent self-registration
  /agent/download                 Agent JAR download
  /agent/version                  Agent version info
  /api/v1/auth/config             Auth config (OAuth2 client ID, dev mode flag)
  /api/v1/auth/logout             Logout
  /api/v1/auth/dev-token          Dev-mode JWT issuance
  /api/v1/auth/invite/**          Invite acceptance flow
  /oauth2/**                      Google OAuth2 redirect
  /login/oauth2/**                Google OAuth2 callback
  <any other non-/api/ request>   SPA catch-all (React Router)

authenticated:
  /api/v1/**                      All other API endpoints
```

Sessions are stateless (`SessionCreationPolicy.STATELESS`). CSRF is disabled (no cookies, stateless API). CORS allows all origin patterns with credentials.

### Agent Certificate Authority (mTLS)

`AgentCertificateAuthority` uses BouncyCastle to maintain a self-signed CA. The CA's public certificate is served at `/api/v1/agent/ca-cert`. Agent client certificates (365-day validity) are issued at registration for mTLS use.

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

All database and infrastructure ports are bound to `127.0.0.1` only. Only the application port (`58244`) is reachable externally.

### Dockerfile

The Dockerfile is a **single-stage runtime image** — the JAR is built on the host first:

```bash
mvn clean package -DskipTests   # Build JAR
docker compose build             # Build image
docker compose up -d
```

Base image: `eclipse-temurin:17-jre-alpine` (JRE only). A non-root `certguard` user runs the process. The TLS keystore is volume-mounted from `./certs` into `/opt/certguard/certs`.

### Startup Ordering

The `app` service declares `depends_on` with `condition: service_healthy` for both `postgres` and `rabbitmq`. The app health check uses `wget` against `https://localhost:8443/actuator/health` with a 60-second start period.

### Named Volumes

`postgres_data`, `rabbitmq_data`, `prometheus_data`, `grafana_data`, and `app_keystores` persist across restarts. Run `docker compose down -v` to wipe all data.

---

## 9. REST API Design

All routes are versioned under `/api/v1/`. The authenticated user's organisation is determined from the JWT (`TenantContext`), not a URL path variable. Role-based access is enforced at the method level via `@PreAuthorize`.

`SpaController` catches all non-API, non-asset requests and returns `index.html` for React Router client-side routing.

### Endpoint Map

```
/api/v1/

├── org/
│   GET    /                        Get current org details
│   GET    /profile                 Get org profile (address, contact info)
│   PUT    /profile                 Update org profile
│   PUT    /name?name=...           Update org name (legacy)
│   GET    /admin/orgs              List all orgs with quotas  [PLATFORM_ADMIN]
│   PUT    /admin/orgs/{orgId}/quota  Update org target quota  [PLATFORM_ADMIN]
│   GET    /members                 List org members
│   POST   /invitations             Invite a member by email   [ADMIN, PLATFORM_ADMIN]
│   PUT    /members/{userId}/role   Change member's role       [ADMIN, PLATFORM_ADMIN]
│   DELETE /members/{userId}        Remove member from org     [ADMIN, PLATFORM_ADMIN]

├── targets/
│   GET    /                        List targets (paginated)
│   POST   /                        Create target
│   PUT    /{id}                    Update target
│   DELETE /{id}                    Delete target
│   POST   /{id}/scan               Trigger immediate scan
│   GET    /{id}/scan-status        Poll latest scan job status
│   GET    /{id}/notifications      Get notification channel config
│   PUT    /{id}/notifications      Update notification channel config

├── certificates/
│   GET    /                        List certificates (paginated)
│   GET    /expiring?days=N         Certificates expiring within N days (default 30)

├── dashboard/
│   GET    /                        Aggregate stats for org dashboard

├── locations/
│   GET    /                        List locations for current org
│   GET    /{id}                    Get location by ID
│   POST   /                        Create location
│   PUT    /{id}                    Update location
│   DELETE /{id}                    Delete location

├── msp/
│   GET    /clients                 List MSP client orgs       [ADMIN, ENGINEER, PLATFORM_ADMIN]
│   GET    /clients/{clientOrgId}   Get client org details     [ADMIN, ENGINEER, PLATFORM_ADMIN]
│   POST   /clients                 Create client org          [ADMIN, PLATFORM_ADMIN]
│   PUT    /clients/{clientOrgId}   Update client org          [ADMIN, PLATFORM_ADMIN]

├── admin/                          (all require SUPER_ADMIN)
│   GET    /orgs                    List all orgs + subscriptions
│   GET    /orgs/{orgId}/subscription  Get subscription for org
│   PUT    /orgs/{orgId}/subscription/quota  Update quota

└── agent/
    GET    /ca-cert                 Agent CA public cert (no auth)
    POST   /register                Agent self-registration (X-Org-Id + token body)
    POST   /tokens?agentName=...    Generate registration token (JWT auth)
    GET    /config                  Download pre-filled agent config (JWT auth)
    GET    /list                    List agents in org (JWT auth)
    POST   /{agentId}/revoke        Revoke an agent (JWT auth)
    POST   /heartbeat               Agent heartbeat (X-Agent-Key auth)
    GET    /jobs                    Agent polls pending jobs (X-Agent-Key auth)
    POST   /results                 Agent submits scan result (X-Agent-Key auth)

/agent/
    GET    /download                Agent JAR download (no auth)
    GET    /version                 Latest agent version info (no auth)

/api/v1/auth/                       (dev-mode endpoints, disabled when app.dev-mode=false)
    GET    /dev-token               Issue JWT directly
    POST   /invite/accept           Accept org invitation
```

### HTTP Status Codes

| Status | Usage |
|---|---|
| `200 OK` | Successful read or action |
| `201 Created` | Successful POST that creates a resource |
| `204 No Content` | Successful DELETE |
| `400 Bad Request` | Validation failure or business rule violation |
| `401 Unauthorized` | Missing or invalid authentication |
| `403 Forbidden` | Authenticated but insufficient role |
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

MapStruct 1.6.3 is used for entity ↔ DTO conversions. Mappers are `@Mapper(componentModel = "spring")` interfaces whose implementations are generated at compile time. Request DTOs live in `dto/request/`, response DTOs in `dto/response/`.

---

## 10. Error Handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) centralises all exception-to-HTTP mapping. No controller contains try/catch logic.

| Exception | HTTP Status | Thrown when |
|---|---|---|
| `ResourceNotFoundException` | 404 | Entity not found by ID |
| `QuotaExceededException` | 429 | Certificate quota exceeded |
| `IllegalArgumentException` | 400 | Business rule violated (duplicate, invalid input) |
| `IllegalStateException` | 409 | Invalid state transition (e.g., revoking an already-revoked agent) |
| `SecurityException` | 403 | Auth check failed |
| `AccessDeniedException` | 403 | Spring Security method security denial |
| `NoResourceFoundException` | 404 | Spring MVC route not found |
| `Exception` (catch-all) | 500 | Any unhandled exception (logged at ERROR) |

---

## 11. Key Design Decisions

### Multi-Tenancy via TenantContext, Not URL Parameters

The authenticated user's `orgId` is extracted from the JWT and stored in a `ThreadLocal` (`TenantContext`) for the duration of the request. Controllers read `TenantContext.getOrgId()` instead of accepting `{orgId}` as a URL path variable. This prevents IDOR vulnerabilities where a caller could substitute another org's ID. The trade-off is that cross-org admin operations require elevated roles rather than simple URL parameterisation.

### Two-Tier Role System: UserRole vs OrgMemberRole

The codebase maintains two role enums with distinct scopes:

- **`UserRole`** (`PLATFORM_ADMIN | SUPER_ADMIN | ADMIN | MEMBER | VIEWER`) — stored on the `User` entity; represents the user's platform-level role. `PLATFORM_ADMIN` and `SUPER_ADMIN` are operator-level roles that span organisations.
- **`OrgMemberRole`** (`ADMIN | ENGINEER | VIEWER`) — stored on `OrgMember`; represents the user's role *within a specific organisation*. Enforced via `@PreAuthorize` on controllers that manage team membership.

Services that check permissions use `User.role` (global) for `PLATFORM_ADMIN`/`SUPER_ADMIN` gates and `OrgMember.role` (per-org) for org-scoped gates.

### MSP Org Hierarchy

`Organization.org_type` distinguishes standard tenants (`SINGLE`) from Managed Service Providers (`MSP`). An MSP org can own child orgs via the `parent_org_id` self-referential FK. `MspClientService` enforces that only MSP orgs can create client orgs, and that client lookups are scoped to the requesting MSP. This allows MSPs to administer their clients' certificate monitoring from a single account.

### Dual Invitation Paths (Legacy and New)

Two invitation systems coexist:

- **Legacy (`org_invitations` + `OrgMemberService`)**: Stores the raw invite token and uses `UserRole`. Written for a simpler single-org model.
- **New (`invitations` + `TeamService` + `InvitationService`)**: Stores a SHA-256 token hash (plaintext emailed, never stored), uses `OrgMemberRole`, and supports a two-step OTP acceptance flow. New code should use this path.

The split exists because the new system was added incrementally. A future migration should consolidate them.

### Schema Ownership Belongs to Flyway, Not Hibernate

`ddl-auto: none` means Hibernate never touches the schema. Flyway migration scripts are the single source of truth. This makes schema changes explicit, reviewable in code review, and safely applicable in production. It also allows PostgreSQL-specific features (native ENUMs, JSONB, `gen_random_uuid()`, triggers) that Hibernate cannot generate.

### Read-Only Transactions as the Default

`@Transactional(readOnly = true)` at the service class level is the default; write methods override to `@Transactional`. Read-only transactions skip Hibernate dirty-checking, reducing memory pressure on read-heavy paths, and allow future routing of reads to a PostgreSQL read replica without code changes.

### Async Notification Dispatch

`NotificationService.dispatchExpiryAlert()` is annotated `@Async`. Certificate expiry checks trigger this method without blocking the calling thread. A failure to send an email does not roll back the scan write transaction. `@EnableAsync` is on `CertGuardApplication`; the thread pool is configurable via `app.async.*` properties.

### Denormalised `org_id` on `certificate_records` and `agent_scan_jobs`

Both tables carry `org_id` even though it is derivable via a JOIN. Org-wide certificate listing and scan job queries are high-frequency. The denormalisation lets them use `idx_certs_org_id` / `idx_scan_jobs_status` without a JOIN. The FK constraint on `org_id → organizations` ensures consistency; deletions cascade automatically.

### Agents Use BCrypt for Key Storage, Not Reversible Encryption

Agent API keys are 64-character random strings shown once at registration. Only the BCrypt hash is stored. Lost keys cannot be recovered — the operator must revoke the agent and register a new one.

### Application Serves Both API and SPA

`SpaController` catches all non-API, non-asset requests and returns `index.html`, enabling React Router's client-side routing without a separate NGINX process. Static assets are served from `classpath:/static/`. NGINX was explicitly removed from the stack since Spring Boot handles HTTPS directly.

### Non-Root Container Process

The Spring Boot container runs as `certguard:certguard` (non-root). If a vulnerability were exploited, the attacker's capabilities would be limited to that user's privileges inside the container.
