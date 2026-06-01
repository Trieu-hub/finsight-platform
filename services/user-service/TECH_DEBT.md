# User Service — Tech Debt

## Security Improvements

- **`updateProfile` cannot clear fields to null** — all update guards are `if (field != null)`. A client cannot intentionally remove a phone number or avatar URL. Requires `Optional<T>` fields or JSON Merge Patch (`PATCH` endpoint) to distinguish "absent" from "null".
- **No JWT expiry validation beyond signature** — `JwtService.validateToken()` relies on JJWT throwing on expiry, but there is no explicit clock skew tolerance or max-age enforcement. Consider adding `JwtParserBuilder.clockSkewSeconds()`.
- **JWT secret minimum-length is not enforced at startup** — if `JWT_SECRET` is set but shorter than 32 bytes, JJWT will reject it at first token validation rather than at startup. Add a `@PostConstruct` guard in `JwtService`.

## Refactoring Opportunities

- **Manual `toResponse()` mapping in `UserProfileServiceImpl`** — hand-rolled field-by-field mapping. A mapping library (e.g., MapStruct) would eliminate the boilerplate and keep mapping in sync as fields are added.
- **`@TestPropertySource` dialect warning in tests** — `spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect` is deprecated; H2Dialect auto-detects in Hibernate 6+. The property can be removed from the test.

## Missing Tests

- No unit tests for `JwtService` (token validation, claim extraction, malformed inputs).
- No unit tests for `UserProfileServiceImpl` (create/get/update logic, duplicate guard).
- No slice tests (`@WebMvcTest`) for `UserProfileController` — verifying request validation and HTTP status codes without a full context.
- No test covering the TOCTOU race path (`DataIntegrityViolationException` → 409).

## Future Integration with Auth Service

- **No cross-service user existence check** — user-service cannot verify that a `userId` from a JWT maps to an active, non-deleted user in auth-service. Options: internal HTTP endpoint (`GET /internal/users/{id}/exists`) or event-driven invalidation (Kafka `user.disabled` event).
- **Shared JWT secret coordination** — both services must rotate the secret in lockstep. A versioned key strategy (e.g., key ID in JWT header) would allow zero-downtime rotation.

## Future Integration with Transaction Service

- **No user profile event publishing** — when a profile is created or updated, downstream services (e.g., transaction-service for display names) have no way to learn about the change. An outbox pattern or direct event publishing would be needed.
- **No pagination or search API** — transaction-service or admin tooling may need `GET /api/v1/users?ids=1,2,3` bulk lookups. Not needed for Phase 1 but will be required before transaction-service integration.
