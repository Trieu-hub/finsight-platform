# user-service — Architecture Snapshot

## Tổng quan

| Thuộc tính | Giá trị |
|---|---|
| Artifact | `com.pm:user-service:0.0.1-SNAPSHOT` |
| Spring Boot | 4.0.6 |
| Java | 21 |
| Port | 8082 |
| Database | PostgreSQL (`user_db`) |
| Cache | Không có |
| Base package | `com.pm.userservice` |

---

## Package Structure

```
com.pm.userservice/
├── UserServiceApplication.java        — @SpringBootApplication, @EnableConfigurationProperties
├── config/
│   └── AuditingConfig.java            — @EnableJpaAuditing
├── controller/
│   └── UserProfileController.java     — /api/v1/users/*
├── dto/
│   ├── CreateProfileRequest.java      — Bean Validation + @NotBlank, @Pattern, @Past, @Size
│   ├── UpdateProfileRequest.java      — tất cả field optional
│   └── UserProfileResponse.java       — @Builder
├── entity/
│   └── UserProfile.java               — @EntityListeners(AuditingEntityListener.class)
├── exception/
│   ├── ErrorResponse.java
│   ├── GlobalExceptionHandler.java    — @RestControllerAdvice
│   ├── ProfileAlreadyExistsException.java
│   └── ProfileNotFoundException.java
├── repository/
│   └── UserProfileRepository.java     — JpaRepository<UserProfile, Long>
├── security/
│   ├── JwtUserPrincipal.java          — giữ userId, email, role; không implements UserDetails
│   ├── SecurityConfig.java            — SecurityFilterChain, stateless
│   └── jwt/
│       ├── JwtAuthenticationFilter.java — OncePerRequestFilter
│       ├── JwtProperties.java           — @ConfigurationProperties(prefix = "jwt")
│       └── JwtService.java              — validate + extract claims (không generate token)
└── service/
    ├── UserProfileService.java        — interface
    └── impl/
        └── UserProfileServiceImpl.java
```

---

## Database Schema

**Database:** PostgreSQL `user_db`  
**Migration engine:** Flyway (1 migration)

### `user_profiles`

```sql
CREATE TABLE user_profiles (
    user_id       BIGINT       NOT NULL,    -- PK, nguồn từ JWT claim "userId" (không auto-generate)
    full_name     VARCHAR(100),
    phone         VARCHAR(20),
    date_of_birth DATE,
    avatar_url    VARCHAR(255),
    occupation    VARCHAR(100),
    bio           VARCHAR(500),
    created_at    TIMESTAMP,               -- tự set bởi JPA Auditing (@CreatedDate)
    updated_at    TIMESTAMP,               -- tự set bởi JPA Auditing (@LastModifiedDate)
    CONSTRAINT pk_user_profiles PRIMARY KEY (user_id)
);
```

**Không có FK** tới `auth_db.users` — hai service dùng database hoàn toàn độc lập.

### Migration history

| Version | File | Nội dung |
|---|---|---|
| V1 | `V1__create_user_profiles.sql` | Tạo `user_profiles` |

---

## API Endpoints

Base path: `/api/v1/users`

| Method | Path | Auth | Request Body | Response |
|---|---|---|---|---|
| `POST` | `/me` | Bearer JWT | `CreateProfileRequest` | `UserProfileResponse` — `201 Created` |
| `GET`  | `/me` | Bearer JWT | — | `UserProfileResponse` — `200 OK` |
| `PUT`  | `/me` | Bearer JWT | `UpdateProfileRequest` | `UserProfileResponse` — `200 OK` |
| `GET`  | `/actuator/health` | Public | — | health status |
| `GET`  | `/actuator/info` | Public | — | service info |

### Request Shapes

**CreateProfileRequest**
```json
{
  "fullName":    "string (bắt buộc, max 100)",
  "phone":       "string (optional, regex: ^[+]?[0-9\\s\\-()]{7,20}$)",
  "dateOfBirth": "YYYY-MM-DD (optional, phải là ngày trong quá khứ)",
  "avatarUrl":   "string (optional, max 255)",
  "occupation":  "string (optional, max 100)",
  "bio":         "string (optional, max 500)"
}
```

**UpdateProfileRequest** — giống CreateProfileRequest nhưng tất cả field đều optional, không có `@NotBlank` trên `fullName`.

**Lưu ý:** null trong UpdateRequest = "không thay đổi field đó" (không thể xóa/clear field về null).

### UserProfileResponse Shape

```json
{
  "userId":      1,
  "fullName":    "Nguyen Van A",
  "phone":       "+84901234567",
  "dateOfBirth": "1995-06-15",
  "avatarUrl":   "https://...",
  "occupation":  "Engineer",
  "bio":         "...",
  "createdAt":   "2026-06-01T10:00:00",
  "updatedAt":   "2026-06-01T10:00:00"
}
```

### Error Response Shape

```json
{
  "success": false,
  "message": "Profile not found"
}
```

### HTTP Status Codes

| Tình huống | Code |
|---|---|
| Tạo thành công | `201 Created` |
| Lấy/cập nhật thành công | `200 OK` |
| Validation lỗi | `400 Bad Request` |
| Không có / sai JWT | `401 Unauthorized` |
| Profile không tồn tại | `404 Not Found` |
| Profile đã tồn tại (hoặc race condition duplicate) | `409 Conflict` |
| Lỗi không xác định | `500 Internal Server Error` |

---

## JWT Claims

**user-service chỉ đọc JWT, không tạo.** Token được tạo bởi auth-service và ký bằng cùng secret.

Claims được đọc bởi `JwtService`:

| Claim | Type | Cách đọc |
|---|---|---|
| `userId` | Number → Long | `parseClaims(token).get("userId")` → cast `(Number).longValue()` |
| `email` | String | `parseClaims(token).get("email", String.class)` |
| `role` | String | `parseClaims(token).get("role", String.class)` |

**`JwtUserPrincipal`** — principal được lưu trong SecurityContext sau khi filter xử lý:

```java
public class JwtUserPrincipal {
    Long   userId;   // từ claim "userId"
    String email;    // từ claim "email"
    String role;     // từ claim "role" (VD: "ROLE_USER")
}
```

`getAuthorities()` trả về `ROLE_` prefix nếu role chưa có (normalize tự động).

---

## Security Flow

```
HTTP Request
  │
  ▼
JwtAuthenticationFilter.doFilterInternal()
  │
  ├── Không có "Authorization: Bearer ..." → chain through → Spring Security trả 401
  │
  ├── Token invalid/expired → response.sendError(401) ngay lập tức (không chain)
  │
  └── Token hợp lệ + SecurityContext chưa có auth
       ├── extractUserId(token) → Long
       ├── extractEmail(token) → String
       ├── extractRole(token) → String
       ├── Build JwtUserPrincipal(userId, email, role)
       ├── Build UsernamePasswordAuthenticationToken(principal, null, authorities)
       └── Store in SecurityContextHolder
  │
  ▼
SecurityConfig Authorization:
  ├── /actuator/health, /actuator/info → permitAll
  └── anyRequest → authenticated
  │
  ▼
UserProfileController
  └── extractUserId(authentication)
       └── (JwtUserPrincipal) authentication.getPrincipal()
            └── principal.getUserId()  ← userId LUÔN từ JWT, không bao giờ từ request
```

**Không có DB lookup trong filter** — khác biệt chủ yếu so với auth-service.

---

## JPA Auditing

`@EnableJpaAuditing` khai báo trong `AuditingConfig`.

Entity `UserProfile` có:
```java
@CreatedDate
@Column(name = "created_at", updatable = false)
private LocalDateTime createdAt;   // set tự động khi INSERT

@LastModifiedDate
@Column(name = "updated_at")
private LocalDateTime updatedAt;   // set tự động mỗi khi UPDATE
```

Cần `@EntityListeners(AuditingEntityListener.class)` trên entity.

---

## Dependencies

### Runtime

| Dependency | Phiên bản | Mục đích |
|---|---|---|
| `spring-boot-starter-webmvc` | 4.0.6 | MVC, servlet |
| `spring-boot-starter-security` | 4.0.6 | Security filter chain |
| `spring-boot-starter-data-jpa` | 4.0.6 | Hibernate ORM, JPA Auditing |
| `spring-boot-starter-validation` | 4.0.6 | Bean Validation |
| `spring-boot-starter-actuator` | 4.0.6 | Health/info endpoints |
| `spring-boot-starter-flyway` | 4.0.6 | DB migration (PostgreSQL) |
| `jjwt-api` / `jjwt-impl` / `jjwt-jackson` | 0.12.6 | JWT parse + validate |
| `postgresql` | (managed) | JDBC driver |
| `lombok` | (managed) | Code generation |

### Test

| Dependency | Mục đích |
|---|---|
| `spring-boot-starter-test` | JUnit 6, Mockito 5, AssertJ |
| `spring-security-test` | `SecurityMockMvcRequestPostProcessors`, `SecurityMockMvcConfigurers` |
| `h2` | In-memory DB cho integration test |

---

## Configuration (application.yml)

```yaml
server.port: 8082

spring.datasource:
  url: ${DB_URL:jdbc:postgresql://localhost:5432/user_db}
  username: ${DB_USERNAME:postgres}
  password: ${DB_PASSWORD:}           # mặc định empty nếu không set env var

spring.jpa.hibernate.ddl-auto: validate
spring.jpa.open-in-view: false

spring.flyway.enabled: true           # baseline-on-migrate không set (mặc định false)

jwt:
  secret: ${JWT_SECRET}               # bắt buộc — không có fallback, fail fast nếu thiếu

management:
  endpoints.web.exposure.include: health,info
  health.redis.enabled: false         # không có Redis
```

**Biến môi trường bắt buộc:**

| Biến | Mô tả |
|---|---|
| `JWT_SECRET` | Phải trùng với auth-service, min 256-bit |
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USERNAME` | DB user |
| `DB_PASSWORD` | DB password |

---

## Conventions

- **userId:** **luôn** lấy từ `JwtUserPrincipal`, không bao giờ từ path/query/body.
- **Error response:** `ErrorResponse { success: false, message: "..." }` — class riêng, khác với auth-service.
- **Null trong update:** null field trong `UpdateProfileRequest` = "giữ nguyên giá trị cũ" (không thể clear về null).
- **Không có `UserDetailsService`:** security hoàn toàn stateless, không query DB trong filter.
- **Mapping thủ công:** `toResponse()` trong `UserProfileServiceImpl` — không dùng thư viện mapping.
- **DataIntegrityViolationException:** được handle về `409 Conflict` để bắt race condition duplicate profile.
- **Role authority:** được normalize về format `ROLE_*` tại `JwtUserPrincipal.getAuthorities()`.

---

## Quan hệ với auth-service

| Điểm kết nối | Chi tiết |
|---|---|
| Shared JWT secret | `JWT_SECRET` env var phải giống hệt nhau ở cả hai service |
| `userId` claim | auth-service phát ra `userId` (Long), user-service đọc làm PK của `user_profiles` |
| `role` claim | auth-service phát ra `"ROLE_USER"` (với prefix), user-service đọc làm authority |
| Không có HTTP call | user-service không gọi HTTP đến auth-service trong bất kỳ flow nào hiện tại |
| Không có shared DB | `auth_db` (MySQL) và `user_db` (PostgreSQL) hoàn toàn độc lập |
