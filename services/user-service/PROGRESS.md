# User Service Progress

## Phase 1 — Foundation

- [x] Project bootstrap — Spring Boot 4.0.6, Java 21, Maven wrapper
- [x] Configuration — `application.yml` with env-var-driven DB and JWT settings; `JwtProperties` bound via `@EnableConfigurationProperties`
- [x] Database setup — PostgreSQL `user_db`; Flyway migration `V1__create_user_profiles.sql`
- [x] Entity — `UserProfile` with JPA Auditing (`@CreatedDate` / `@LastModifiedDate`)
- [x] Repository — `UserProfileRepository` (Spring Data JPA)
- [x] DTOs — `CreateProfileRequest`, `UpdateProfileRequest`, `UserProfileResponse` with Bean Validation
- [x] Security — JWT-claims-based auth; `JwtAuthenticationFilter`, `JwtService`, `JwtUserPrincipal`, `SecurityConfig` (stateless, no `UserDetailsService`)
- [x] Services — `UserProfileService` interface + `UserProfileServiceImpl`
- [x] Controllers — `UserProfileController` (`POST /me`, `GET /me`, `PUT /me`)
- [x] Exception handling — `GlobalExceptionHandler` with typed handlers + `DataIntegrityViolationException` → 409
- [x] Validation — Bean Validation on all DTOs (`@NotBlank`, `@Size`, `@Past`, `@Pattern`)
- [x] Testing — `UserServiceApplicationTests` context-loads with H2 in-memory DB
- [x] Documentation — `CLAUDE.md`, `USER_SERVICE_PHASE1_SUMMARY.md`

## Phase 1.1 — Hardening

- [x] Config cleanup — removed deprecated `hibernate.dialect`; added `open-in-view: false`; removed hardcoded JWT secret fallback (completed in prior session)
- [x] Test cleanup — fixed H2 dialect mismatch (resolved via YAML fix); zero Hibernate/Spring warnings in test run
- [x] Service tests — `UserProfileServiceTest`: 7 unit tests covering createProfile, getMyProfile, updateProfile (success + error paths)
- [x] Controller tests — `UserProfileControllerTest`: 15 MockMvc tests covering POST/GET/PUT endpoints, validation errors, and HTTP status codes
- [x] Security tests — missing JWT → 401, invalid JWT → 401, authenticated request → 200 (included in `UserProfileControllerTest`)
