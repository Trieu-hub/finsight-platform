# FinSight — Trả lời các câu hỏi

**1. Service gọi nhau qua REST nội bộ thật, hay phần lớn qua Kafka, REST chỉ ở FE/gateway?**
Business service KHÔNG gọi REST trực tiếp lẫn nhau. Giao tiếp nghiệp vụ giữa chúng đi qua Kafka (event-driven). REST chỉ ở 2 chỗ: api-gateway (proxy FE → service) và dashboard-service (BFF, đọc tổng hợp từ transaction/budget/user qua RestClient, relay JWT).

**2. FE của FinSight là gì — viết bằng gì, có chức năng gì?**
React 19 + TypeScript + Vite + TailwindCSS v4 + React Router + Axios. Chức năng: auth (login/register) với silent JWT refresh, dashboard (income/expense/balance, spending-by-category, budget bars), transactions (tạo + list, validate category khớp type), budgets, admin console RBAC (list/đổi role/enable-disable/xóa user). CV nên ghi full-stack, không phải thuần backend.

**3. Idempotent consumer (A2) implement thế nào?**
budget-service dùng bảng `processed_events` (inbox pattern): consumer check eventId đã xử lý chưa, có thì bỏ qua, chưa thì xử lý + ghi vào bảng cùng transaction. risk-service idempotent bằng cách key row theo source id. Vì Kafka là at-least-once nên consumer phải tự idempotent.

**4. Redis (D4) dùng để làm gì cụ thể?**
2 việc trong auth-service: (1) Refresh token store (`refresh:token:{token}→userId`, `refresh:user:{userId}→token`, TTL 7 ngày, rotation/revoke khi logout); (2) Brute-force lockout (`login:attempts:{email}` đếm fail, quá 5 lần thì set `login:lock:{email}` 15 phút). Dùng TTL của Redis nên không cần cleanup job. (File auth-service/CLAUDE.md ghi "Redis not yet implemented" là lỗi thời.)

**5. 8 service và 3 topic:**
Service (theo port): 8080 api-gateway, 8081 auth-service, 8082 user-service, 8083 transaction-service (producer), 8084 budget-service (producer + consumer), 8085 dashboard-service (BFF, không DB), 8086 risk-service (consumer + producer, nội bộ), 8087 notification-service (consumer của RiskDetected, tạo in-app notification).
Topic: `finsight.transactions.created` (TransactionCreated), `finsight.budgets.changed` (BudgetChanged), `finsight.risk.detected` (RiskDetected — nay đã có consumer là notification-service).
(Ghi chú: trước đây hệ thống thiếu 2 service so với charter — Notification và Analytics. Notification đã build xong; **chỉ còn Analytics** là chưa có, dashboard-service mới chỉ trình bày chứ không phân tích.)

**6. API Gateway dùng cái gì, và Saga (A8) đã code chưa?**
Không phải Spring Cloud Gateway. Tự viết reverse proxy: một @RestController catch-all `/**` (Spring MVC + RestClient), resolve service đích theo path-prefix, enforce JWT ở rìa, forward method/header/body và relay response. Saga: chưa code, mới biết khái niệm. Project xử lý nhất quán bằng event Kafka + idempotency inbox, không phải Saga.

**7. Đã viết test JUnit/Testcontainers thật chưa? (E5)**
Có, nhiều. Testcontainers (MySQL container thật) qua AbstractMySqlIntegrationTest ở transaction/budget/user/risk-service. MockMvc + @SpringBootTest cho CRUD, filter, security (401/403), event publishing, Prometheus, OpenAPI. auth-service test refresh token, login lockout, disabled account, JWT claims. Đây là điểm mạnh, nên khoe (ví dụ TransactionEventPublishingIntegrationTest test event ra Kafka).

**8. Lỗi Docker network (B4) "localhost vs service name" có đúng là lỗi bạn từng gặp không?**
Code không xác nhận hộ được — đây là câu chuyện cá nhân, chỉ kể nếu thật. Sự thật trong repo: docker-compose dùng service name (vd finsight-mysql) cho container nói chuyện, nên khái niệm là thật. Nếu kể: triệu chứng Connection refused khi connect localhost:3306, nguyên nhân localhost trong container là chính nó chứ không phải MySQL, fix bằng đổi sang service name. Nếu không thật thì chọn bug khác từng gặp thật (vd "No route matches /api/v1/categories").

---

# Danh sách toàn bộ test trong repo

Tổng: 73 class trong `src/test`, trong đó **63 class test thật** + 10 class hỗ trợ (Abstract* base class, JwtTestTokens). Liệt kê theo service.

## api-gateway (3)
- `PrometheusEndpointTest` — /actuator/prometheus expose metrics
- `proxy/GatewayAuthTest` — edge auth: thiếu/sai/hết hạn token → 401 đúng mã lỗi
- `proxy/GatewayRoutingTest` — route theo path-prefix, public route, route không tồn tại

## auth-service (11)
- `AuthServiceApplicationTests` — context load
- `security/jwt/JwtServiceTest` — sinh/parse JWT, claims, hết hạn
- `integration/AuthApiIntegrationTest` — register/login/refresh/logout
- `integration/RefreshTokenIntegrationTest` — refresh token Redis, rotation, revoke
- `integration/LoginLockoutIntegrationTest` — khóa account sau N lần login fail
- `integration/DisabledAccountIntegrationTest` — account bị disable không login được
- `integration/JwtClaimsIntegrationTest` — claims (userId, email, role, iss, aud)
- `integration/JwtSecurityIntegrationTest` — token sai chữ ký/secret bị từ chối
- `integration/PasswordEncodingIntegrationTest` — mật khẩu lưu bcrypt, không plaintext
- `integration/OpenApiDocsIntegrationTest` — /v3/api-docs
- `integration/PrometheusEndpointIntegrationTest` — metrics

## user-service (8)
- `UserServiceApplicationTests` — context load
- `security/jwt/JwtServiceTest` — JWT
- `controller/UserProfileControllerTest` — controller (MockMvc)
- `service/UserProfileServiceTest` — service logic
- `integration/UserProfileApiIntegrationTest` — CRUD profile
- `integration/UserProfileApiSecurityIntegrationTest` — 401/403, IDOR (không xem profile người khác)
- `integration/OpenApiDocsIntegrationTest` — /v3/api-docs
- `integration/PrometheusEndpointIntegrationTest` — metrics

## transaction-service (11)
- `CurrencyValidatorTest` — validate mã tiền tệ
- `security/jwt/JwtServiceTest` — JWT
- `integration/TransactionApiCrudIntegrationTest` — tạo/đọc/sửa/xóa giao dịch
- `integration/TransactionApiFilterIntegrationTest` — lọc theo ngày/category/type
- `integration/TransactionApiSecurityIntegrationTest` — 401/403, phân quyền theo user
- `integration/TransactionPersistenceIntegrationTest` — lưu DB đúng (Testcontainers MySQL)
- `integration/TransactionSummaryIntegrationTest` — summary categories/monthly/trend
- `integration/TransactionEventPublishingIntegrationTest` — publish TransactionCreated ra Kafka (AFTER_COMMIT)
- `integration/CategoryApiIntegrationTest` — danh mục seed
- `integration/SchemaIndexIntegrationTest` — schema/index Flyway
- `integration/PrometheusEndpointIntegrationTest` — metrics

## budget-service (14)
- `CurrencyValidatorTest` — validate mã tiền tệ
- `security/jwt/JwtServiceTest` — JWT
- `config/KafkaConsumerConfigTest` — cấu hình consumer
- `event/TransactionEventConsumerTest` — consume TransactionCreated, cập nhật spent_amount
- `event/BudgetEventListenerTest` — publish BudgetChanged
- `service/BudgetServiceImplTest` — service logic
- `integration/BudgetApiCrudIntegrationTest` — CRUD budget
- `integration/BudgetApiFilterIntegrationTest` — lọc budget
- `integration/BudgetApiSecurityIntegrationTest` — 401/403
- `integration/BudgetPersistenceIntegrationTest` — lưu DB (Testcontainers)
- `integration/BudgetUtilizationConsumerIntegrationTest` — idempotency inbox (processed_events), dedupe event trùng
- `integration/SchemaIndexIntegrationTest` — schema/index
- `integration/OpenApiDocsIntegrationTest` — /v3/api-docs
- `integration/PrometheusEndpointIntegrationTest` — metrics

## risk-service (11)
- `rule/RiskRuleEngineTest` — luật phát hiện rủi ro (HIGH_AMOUNT, RAPID_COUNT, DAILY_THRESHOLD)
- `config/KafkaConsumerConfigTest` — cấu hình consumer
- `event/RiskEventConsumerTest` — consume TransactionCreated, sinh RiskDetected
- `event/BudgetEventConsumerTest` — consume BudgetChanged
- `service/AnomalyServiceTest` — phát hiện bất thường
- `service/InsightServiceTest` — insight logic
- `integration/RiskDetectionIntegrationTest` — luồng phát hiện rủi ro đầu-cuối
- `integration/InsightAnomalyKafkaE2EIntegrationTest` — E2E qua Kafka (Testcontainers)
- `integration/RiskAlertApiIntegrationTest` — API cảnh báo
- `integration/AnomalyApiIntegrationTest` — API anomaly
- `integration/InsightApiIntegrationTest` — API insight

## dashboard-service (9)
- `DashboardApplicationTests` — context load
- `security/jwt/JwtServiceTest` — JWT
- `service/DashboardServiceTest` — aggregation logic
- `client/TransactionClientTest` — gọi transaction-service (RestClient)
- `client/BudgetClientTest` — gọi budget-service
- `client/UserClientTest` — gọi user-service
- `security/DashboardApiSecurityIntegrationTest` — 401/403, relay JWT
- `OpenApiDocsTest` — /v3/api-docs
- `PrometheusEndpointTest` — metrics

## Class hỗ trợ (không phải test, dùng chung)
- `*/integration/AbstractMySqlIntegrationTest` — base bật MySQL Testcontainers (transaction, budget, risk, user)
- `*/integration/AbstractMockMvcIntegrationTest` — base MockMvc (auth, budget, transaction, risk)
- `auth-service/integration/AbstractIntegrationTest` — base auth (MySQL + Redis)
- `*/support/JwtTestTokens` + `gateway/support/JwtTestTokens` — sinh token test
