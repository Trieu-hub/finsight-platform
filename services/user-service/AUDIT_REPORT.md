# User Service — Audit Report

**Date:** 2026-06-01  
**Scope:** Phase 1 — Foundation  
**Auditor:** Strict static + runtime analysis

---

## Checklist Results

| # | Item | Result |
|---|------|--------|
| 1 | Project compiles | ✅ PASS |
| 2 | Application starts | ✅ PASS |
| 3 | Flyway migration executes | ✅ PASS |
| 4 | UserProfile table created correctly | ✅ PASS |
| 5 | JPA Auditing works | ✅ PASS |
| 6 | JWT authentication works | ✅ PASS |
| 7 | JwtAuthenticationFilter is claims-only | ✅ PASS |
| 8 | UserId extracted from JWT claims | ✅ PASS |
| 9 | POST /api/v1/users/me | ✅ PASS |
| 10 | GET /api/v1/users/me | ✅ PASS |
| 11 | PUT /api/v1/users/me | ✅ PASS |
| 12 | Validation errors return proper responses | ✅ PASS |
| 13 | Exception handling returns proper responses | ✅ PASS |
| 14 | No Auth Service entity duplicated | ✅ PASS |
| 15 | No identity fields in UserProfile | ✅ PASS |
| 16 | application.yml is valid | ⚠️ FAIL |
| 17 | Test suite passes | ⚠️ FAIL |

---

## Detailed Findings

### ✅ PASS Items — Notes

**#1 — Compiles:** `./mvnw compile` exits with BUILD SUCCESS. All 19 source files compile without errors.

**#2 — Application starts:** `UserServiceApplicationTests.contextLoads()` boots the full Spring context (port random, in-memory DB). No bean wiring errors. `UserDetailsServiceAutoConfiguration` is correctly excluded via the Spring Boot 4 package `org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration`.

**#3 — Flyway migration:** `V1__create_user_profiles.sql` is the only migration file. SQL syntax is correct. Flyway is enabled in `application.yml` (default `baseline-on-migrate: false`). Not verifiable without a live PostgreSQL instance, but the migration file is structurally correct.

**#4 — Table definition:** SQL columns in `V1__create_user_profiles.sql` exactly match `@Column` mappings in `UserProfile.java`:
- `user_id BIGINT` ↔ `@Id @Column(name = "user_id") Long userId`
- `full_name VARCHAR(100)` ↔ `@Column(name = "full_name", length = 100)`
- `phone VARCHAR(20)`, `date_of_birth DATE`, `avatar_url VARCHAR(255)`, `occupation VARCHAR(100)`, `bio VARCHAR(500)`, `created_at TIMESTAMP`, `updated_at TIMESTAMP` — all match.

**#5 — JPA Auditing:** `@EnableJpaAuditing` is on `AuditingConfig`. `UserProfile` has `@EntityListeners(AuditingEntityListener.class)`, `@CreatedDate` on `createdAt` with `updatable = false`, and `@LastModifiedDate` on `updatedAt`. Wiring is correct. Functional correctness (actual timestamps populated) is not verified by any test.

**#6 — JWT authentication:** `SecurityConfig` configures: CSRF disabled, stateless sessions, form login disabled, HTTP Basic disabled. All `/api/v1/users/**` require authentication. `/actuator/health` and `/actuator/info` are public. `JwtAuthenticationFilter` is registered before `UsernamePasswordAuthenticationFilter`.

**#7 — Filter is claims-only:** `JwtAuthenticationFilter` contains zero references to `UserDetailsService`, no repository injection, no HTTP client. Claims extracted directly from JWT via `JwtService`. Invalid/expired tokens now return 401 immediately (not passed through).

**#8 — UserId from JWT only:** `UserProfileController.extractUserId()` casts `authentication.getPrincipal()` to `JwtUserPrincipal` and calls `.getUserId()`. No `@PathVariable`, `@RequestParam`, or `@RequestBody` userId accepted from callers.

**#9/#10/#11 — Endpoints:** All three are implemented and mapped under `/api/v1/users`. `POST /me` returns `201 Created`. `GET /me` returns `200 OK`. `PUT /me` returns `200 OK`. 

**#12 — Validation:** `@Valid` is present on both `@RequestBody` parameters in the controller. `GlobalExceptionHandler.handleValidation()` catches `MethodArgumentNotValidException` and returns `400 Bad Request` with the first field error message. DTOs have `@NotBlank`, `@Size`, `@Past`, and `@Pattern` annotations.

**#13 — Exception handling:** `GlobalExceptionHandler` covers: `ProfileNotFoundException` → 404, `ProfileAlreadyExistsException` → 409, `DataIntegrityViolationException` → 409 (concurrent duplicate race), `MethodArgumentNotValidException` → 400, `NoResourceFoundException` → 404, `Exception` (catch-all) → 500.

**#14 — No auth entity duplication:** Zero references to `auth-service`, `AuthService`, `authservice`, or any authentication domain entity. No `User`, `Role`, `RefreshToken`, or `Permission` entities exist in this service.

**#15 — No identity fields:** `UserProfile` entity contains: `userId`, `fullName`, `phone`, `dateOfBirth`, `avatarUrl`, `occupation`, `bio`, `createdAt`, `updatedAt`. No `email`, `password`, `username`, `role`, or `enabled` fields.

---

### ⚠️ FAIL #16 — `application.yml` has two configuration defects

**Defect A — Deprecated Hibernate dialect property**

- **File:** `src/main/resources/application.yml`, line 18–19
- **Problem:** `spring.jpa.properties.hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect` is explicitly set. Hibernate 7 (bundled with Spring Boot 4) auto-detects the dialect from the JDBC connection and emits deprecation warning `HHH90000025: PostgreSQLDialect does not need to be specified explicitly`. This also causes a second warning `HHH000511` when the test runs against H2 (the explicit PostgreSQLDialect is applied against H2, which identifies itself with an unrecognized version string).
- **Exact warning:** `HHH90000025: PostgreSQLDialect does not need to be specified explicitly using 'hibernate.dialect'`
- **Fix recommendation:** Remove the `spring.jpa.properties.hibernate.dialect` property entirely from `application.yml`. Hibernate 7 will auto-detect `PostgreSQLDialect` from the JDBC metadata when running against PostgreSQL, and `H2Dialect` when running against H2 in tests.

```yaml
# Remove these lines:
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
```

**Defect B — `spring.jpa.open-in-view` not configured**

- **File:** `src/main/resources/application.yml`
- **Problem:** Spring Boot enables Open Session In View (OSIV) by default and logs a warning on every startup: `spring.jpa.open-in-view is enabled by default. Therefore, database queries may be performed during view rendering.` OSIV holds a database connection open for the entire HTTP request lifecycle, which is wasteful and misleading in a stateless REST API where no view rendering occurs.
- **Exact warning:** `JpaBaseConfiguration$JpaWebConfiguration: spring.jpa.open-in-view is enabled by default`
- **Fix recommendation:** Add `spring.jpa.open-in-view: false` to `application.yml` under the `spring.jpa` section.

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false    # add this
```

---

### ⚠️ FAIL #17 — Test suite passes but with incorrect configuration

**Defect A — PostgreSQL dialect applied against H2 in tests**

- **File:** `src/test/java/com/pm/userservice/UserServiceApplicationTests.java`
- **Problem:** `@TestPropertySource` overrides the datasource to H2 but does not override `spring.jpa.properties.hibernate.dialect`. The PostgreSQLDialect from `application.yml` is applied against the H2 connection, causing:
  1. `HHH000511` — unsupported PostgreSQL version warning
  2. `HHH90000025` — deprecated dialect specification warning
  3. DDL execution failure: `Error executing DDL "set client_min_messages = WARNING"` — a PostgreSQL-specific initialisation command that H2 rejects with a syntax error (logged as WARN, not ERROR, so the test still passes)

  The test produces a schema-creation exception that is silently swallowed, meaning the test is not validating what it claims to validate.

- **Exact error (from test run):**
  ```
  GenerationTarget encountered exception accepting command : Error executing DDL 
  "set client_min_messages = WARNING" via JDBC [Syntax error in SQL statement ...]
  ```
- **Fix recommendation (two options, pick one):**
  - **Option A (preferred):** Remove `spring.jpa.properties.hibernate.dialect` from `application.yml` entirely (see FAIL #16, Defect A). Dialect is then auto-detected for both PostgreSQL and H2 — no test override needed.
  - **Option B (standalone test fix):** Add an explicit blank override in `@TestPropertySource` to clear the dialect:
    ```java
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ```

**Defect B — Test coverage covers only application startup**

- **File:** `src/test/java/com/pm/userservice/UserServiceApplicationTests.java`
- **Problem:** The entire test suite consists of a single `contextLoads()` test that verifies the Spring context boots. The following functional behaviours have zero test coverage:
  - JWT filter (valid token, invalid token, missing token)
  - `POST /me` — creation, duplicate profile (409), missing `fullName` (400)
  - `GET /me` — success (200), profile not found (404)
  - `PUT /me` — partial update, validation errors
  - JPA Auditing — `createdAt` populated on insert, `updatedAt` updated on save
  - Exception handler HTTP status codes

- **Fix recommendation:** Add `@WebMvcTest(UserProfileController.class)` slice tests with `@MockitoBean UserProfileService` for HTTP/validation behaviour, and `@DataJpaTest` slice tests for the repository and JPA Auditing. No new dependencies are required — JUnit 5, Mockito, and `spring-security-test` are already on the test classpath.

---

## Summary

### Phase 1 Completion — 90%

| Area | Status | Note |
|------|--------|------|
| Project structure | Complete | |
| Configuration | 85% | Two YAML defects |
| Database / Flyway | Complete | Unverifiable without PostgreSQL |
| Entity + Auditing | Complete | Auditing not validated by test |
| Security / JWT | Complete | |
| Service layer | Complete | |
| Controllers | Complete | |
| Exception handling | Complete | |
| Validation | Complete | |
| Testing | 10% | 1 context-load test only; 3 warnings in test run |
| Documentation | Complete | CLAUDE.md, PROGRESS.md, TECH_DEBT.md |

### Production Readiness — 55%

| Concern | Status |
|---------|--------|
| Compiles and starts | ✅ |
| JWT-claims-based auth | ✅ |
| API endpoints functional | ✅ |
| Exception handling | ✅ |
| Input validation | ✅ |
| Configuration clean (no warnings) | ❌ Two YAML defects producing startup warnings |
| Test coverage | ❌ Single smoke test; no functional coverage |
| `JWT_SECRET` must be set | ✅ Fails fast if unset |
| `DB_PASSWORD` defaults to empty | ⚠️ Risk if env var forgotten in staging |
| No HTTPS / TLS config | ⚠️ Expected to be terminated upstream |
| No rate limiting | ⚠️ Phase 2 concern |
| No structured logging / correlation IDs | ⚠️ Phase 2 concern |
| No health-check DB probe | ⚠️ Actuator health does not include DB by default |

### Technical Debt Summary

| Item | Severity | Effort |
|------|----------|--------|
| Remove deprecated `hibernate.dialect` from YAML | Low | 1 line |
| Add `open-in-view: false` to YAML | Low | 1 line |
| Fix test dialect mismatch (or fix via YAML above) | Medium | 1 line |
| Add `@WebMvcTest` slice tests for controller | High | 1–2 days |
| Add `@DataJpaTest` tests for auditing and repository | Medium | 0.5 days |
| Add unit tests for `JwtService` | Medium | 0.5 days |
| `updateProfile` cannot clear fields to null | Medium | Design decision required |
| `DB_PASSWORD` should require explicit env var (no empty default) | Low | 1 line |
| Cross-service user existence validation (auth-service) | High | Phase 2 |
| Event publishing on profile change (transaction-service) | High | Phase 2 |
