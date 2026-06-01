# User Service — Phase 1 Summary

## 1. Folder Structure

```
user-service/
├── pom.xml
├── mvnw / mvnw.cmd
├── .mvn/wrapper/maven-wrapper.properties
├── src/
│   ├── main/
│   │   ├── java/com/pm/userservice/
│   │   │   ├── UserServiceApplication.java
│   │   │   ├── config/
│   │   │   │   └── AuditingConfig.java
│   │   │   ├── controller/
│   │   │   │   └── UserProfileController.java
│   │   │   ├── dto/
│   │   │   │   ├── CreateProfileRequest.java
│   │   │   │   ├── UpdateProfileRequest.java
│   │   │   │   └── UserProfileResponse.java
│   │   │   ├── entity/
│   │   │   │   └── UserProfile.java
│   │   │   ├── exception/
│   │   │   │   ├── ErrorResponse.java
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   ├── ProfileAlreadyExistsException.java
│   │   │   │   └── ProfileNotFoundException.java
│   │   │   ├── repository/
│   │   │   │   └── UserProfileRepository.java
│   │   │   ├── security/
│   │   │   │   ├── JwtUserPrincipal.java
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   └── jwt/
│   │   │   │       ├── JwtAuthenticationFilter.java
│   │   │   │       ├── JwtProperties.java
│   │   │   │       └── JwtService.java
│   │   │   └── service/
│   │   │       ├── UserProfileService.java
│   │   │       └── impl/
│   │   │           └── UserProfileServiceImpl.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   │           └── V1__create_user_profiles.sql
│   └── test/
│       └── java/com/pm/userservice/
│           └── UserServiceApplicationTests.java
└── USER_SERVICE_PHASE1_SUMMARY.md
```

## 2. Entity Design

`UserProfile` maps to `user_profiles` table.

| Field        | Type          | Notes                              |
|--------------|---------------|------------------------------------|
| userId       | Long (PK)     | Sourced from JWT claim; not auto-generated |
| fullName     | String(100)   | Required on create                 |
| phone        | String(20)    | Optional                           |
| dateOfBirth  | LocalDate     | Optional, must be past             |
| avatarUrl    | String(255)   | Optional                           |
| occupation   | String(100)   | Optional                           |
| bio          | String(500)   | Optional                           |
| createdAt    | LocalDateTime | Auto-set by JPA Auditing on insert |
| updatedAt    | LocalDateTime | Auto-set by JPA Auditing on update |

**Identity fields NOT stored** (belong to auth-service): `email`, `username`, `password`, `role`, `enabled`.

## 3. Security Flow

```
Request
  │
  ▼
JwtAuthenticationFilter.doFilterInternal()
  │
  ├─ No "Authorization: Bearer ..." header → pass through (Spring Security rejects at auth check)
  │
  ├─ Invalid / expired JWT → pass through (Spring Security rejects at auth check)
  │
  └─ Valid JWT
       │
       ├─ Extract claims: userId (Long), email (String), role (String)
       ├─ Build JwtUserPrincipal(userId, email, role)
       ├─ Build UsernamePasswordAuthenticationToken(principal, null, authorities)
       └─ Store in SecurityContextHolder
  │
  ▼
SecurityConfig — all /api/v1/users/** require authentication
  │
  ▼
UserProfileController
  └─ Gets userId via (JwtUserPrincipal) authentication.getPrincipal()
     ← NO userId from request body or URL path
```

No `UserDetailsService`, no database lookup in the security layer.

## 4. API Endpoints

Base path: `/api/v1/users`

| Method | Path | Description                          | Auth Required |
|--------|------|--------------------------------------|---------------|
| POST   | /me  | Create profile for authenticated user | Yes           |
| GET    | /me  | Get profile of authenticated user     | Yes           |
| PUT    | /me  | Update profile of authenticated user  | Yes           |

Additional:

| Method | Path             | Description   | Auth Required |
|--------|------------------|---------------|---------------|
| GET    | /actuator/health | Health check  | No            |
| GET    | /actuator/info   | Service info  | No            |

### Request / Response shapes

**POST /me** — `CreateProfileRequest`:
```json
{
  "fullName": "Nguyen Van A",
  "phone": "+84901234567",
  "dateOfBirth": "1995-06-15",
  "avatarUrl": "https://...",
  "occupation": "Software Engineer",
  "bio": "..."
}
```

**PUT /me** — `UpdateProfileRequest` (all fields optional):
```json
{
  "bio": "Updated bio"
}
```

**Success response** — `UserProfileResponse`:
```json
{
  "userId": 1,
  "fullName": "Nguyen Van A",
  "phone": "+84901234567",
  "dateOfBirth": "1995-06-15",
  "avatarUrl": "...",
  "occupation": "Software Engineer",
  "bio": "...",
  "createdAt": "2026-06-01T10:00:00",
  "updatedAt": "2026-06-01T10:00:00"
}
```

**Error response** — `ErrorResponse`:
```json
{
  "success": false,
  "message": "Profile not found"
}
```

HTTP status codes: `201 Created`, `200 OK`, `400 Bad Request`, `401 Unauthorized`, `404 Not Found`, `409 Conflict`, `500 Internal Server Error`.

## 5. Database Schema

```sql
CREATE TABLE user_profiles (
    user_id       BIGINT       NOT NULL,
    full_name     VARCHAR(100),
    phone         VARCHAR(20),
    date_of_birth DATE,
    avatar_url    VARCHAR(255),
    occupation    VARCHAR(100),
    bio           VARCHAR(500),
    created_at    TIMESTAMP,
    updated_at    TIMESTAMP,
    CONSTRAINT pk_user_profiles PRIMARY KEY (user_id)
);
```

- Database: PostgreSQL (`user_db`)
- Schema managed by Flyway (`ddl-auto: validate`)
- `user_id` is the PK and is sourced directly from the JWT `userId` claim (no FK constraint to auth-service DB — services use separate databases)

## 6. Future Integration Notes

### JWT Secret
Both services share the same JWT secret. Manage this via `JWT_SECRET` environment variable in production — never hardcode. Consider rotating secrets with a versioned key strategy.

### Cross-service User Lookup
Currently user-service has no way to verify that a `userId` in a JWT maps to an active, non-deleted user in auth-service. In Phase 2, consider:
- An internal HTTP endpoint on auth-service: `GET /internal/users/{userId}/exists`
- Or an event-driven approach (Kafka/RabbitMQ) where auth-service publishes `user.disabled` events

### Environment Variables Required
```
DB_URL       = jdbc:postgresql://host:5432/user_db
DB_USERNAME  = postgres
DB_PASSWORD  = <secret>
JWT_SECRET   = <same secret as auth-service>
```

### Planned Phase 2 Features
- Profile picture upload (S3 / MinIO)
- Admin endpoints: `GET /api/v1/users/{userId}`, `DELETE /api/v1/users/{userId}/profile`
- Soft-delete support
- Search / pagination
