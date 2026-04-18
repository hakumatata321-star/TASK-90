# RICMS — Research Innovation Commerce & Service Management

**Project type:** backend

A Spring Boot 3 + PostgreSQL backend implementing membership, order lifecycle,
work orders, outcomes/IP, and interaction governance.

---

## Quick Start

```bash
# 1. Copy environment template (ships with a valid development key).
#    For production: openssl rand -base64 32
cp .env.example .env

# 2. Start all services (database + app)
docker-compose up --build
```

The `app` service waits for the `db` healthcheck before running Flyway migrations
and serving traffic. Expect ~90 s on first run while Maven downloads dependencies
inside the Docker build.

| Component | URL / Port |
|-----------|-----------|
| API       | http://localhost:8080 |
| Postgres  | localhost:5432 (user: `ricms`, db: `ricms`, password: `ricms_secret`) |
| Health    | http://localhost:8080/actuator/health |

### Demo credentials

Seeded on first `docker-compose up` by Flyway migrations:

| Username      | Password        | Role        |
|---------------|-----------------|-------------|
| `admin`       | `Admin@1234!`   | ADMIN       |
| `member`      | `Member@1234!`  | MEMBER      |
| `technician`  | `Tech@1234!`    | TECHNICIAN  |

---

## Running Tests

### Docker-only verification (no local JDK, Maven, or Python required)

```bash
# 1. Start the stack (database + app)
docker-compose up -d --build

# 2. Run unit tests inside Docker (no local Maven/JDK needed)
docker-compose --profile test run --rm unit-tests

# 3. Run API tests
#    curl and python3 are pre-installed on macOS and all major Linux distros.
RICMS_BASE_URL=http://localhost:8080 ./run_tests.sh --api-only
```

### One-command runner (local dev — requires JDK 21+, Maven 3.9+, curl, python3)

```bash
./run_tests.sh
```

Runs both unit tests and API tests and prints a consolidated pass/fail summary.

```bash
./run_tests.sh --unit-only    # Maven unit tests only (no server needed)
./run_tests.sh --api-only     # API tests only (server must be running)
RICMS_BASE_URL=http://host:9090 ./run_tests.sh   # override server URL
```

### Unit tests individually (local dev)

```bash
mvn test
```

Test sources live in `unit_tests/` (added as a Maven test source root via build-helper plugin).

| Test class | What it covers |
|-----------|----------------|
| `PricingEngineTest` | Member pricing gate (Q3), coupon priority chain (Q6), campaign discount, discount cap |
| `MemberServiceTest` | Points floor (Q4), tier boundaries (Q5), upgrade/downgrade rules, status transitions |
| `OrderServiceTest` | Idempotency window (Q8), all-or-nothing inventory (Q9), state machine transitions, timeout release (Q7) |
| `OutcomeServiceTest` | 100%-sum enforcement (Q13), title/abstract duplicate detection (Q14), certificate uniqueness |
| `InteractionServiceTest` | Comment/report rate limits → 429 (Q15), blacklist → 403, sensitive-word → moderation queue |
| `WorkOrderServiceTest` | Double-claim guard, full status progression, cost locking on resolution, rating submission, SLA timestamp tracking |
| `SlaServiceTest` | Business-hours arithmetic, weekend skipping, holiday exclusion, configurable parameters, edge cases |
| `EncryptedStringConverterTest` | AES-256-GCM encrypt/decrypt round-trip, null handling, IV randomness, tamper detection, key validation |

### API tests individually

```bash
# Ensure the server is running first
docker-compose up -d
bash API_tests/01_auth.sh
bash API_tests/02_members.sh
bash API_tests/03_orders.sh
bash API_tests/04_coupons_campaigns.sh
bash API_tests/05_outcomes.sh
bash API_tests/06_interactions.sh
bash API_tests/07_admin.sh
bash API_tests/08_work_orders.sh
bash API_tests/09_rbac.sh
```

---

## Authentication

All endpoints except `POST /v1/auth/login` and `/actuator/**` require a
`Authorization: Bearer <JWT>` header. Tokens are valid for 1 hour.

```bash
# Obtain a token
TOKEN=$(curl -s -X POST http://localhost:8080/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin@1234!"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['accessToken'])")

echo $TOKEN   # eyJhbGci...
```

---

## Example curl Calls

### Users & RBAC

```bash
# List users
curl http://localhost:8080/v1/users \
  -H "Authorization: Bearer $TOKEN"

# Get single user
curl http://localhost:8080/v1/users/<userId> \
  -H "Authorization: Bearer $TOKEN"

# Create user
curl -X POST http://localhost:8080/v1/users \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"Alice@Pass1!"}'

# Assign roles to user
curl -X POST http://localhost:8080/v1/users/<userId>/roles \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"roleIds":["<roleId>"]}'

# List roles & permissions
curl http://localhost:8080/v1/roles \
  -H "Authorization: Bearer $TOKEN"

curl http://localhost:8080/v1/permissions \
  -H "Authorization: Bearer $TOKEN"

# Create role
curl -X POST http://localhost:8080/v1/roles \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"AUDITOR","description":"Read-only auditor role"}'

# Set role permissions
curl -X POST http://localhost:8080/v1/roles/<roleId>/permissions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"permissionIds":["<permId1>","<permId2>"]}'

# Rotate own password
curl -X POST http://localhost:8080/v1/auth/password/rotate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"currentPassword":"Admin@1234!","newPassword":"NewPass@5678!"}'
```

### Membership

```bash
# My profile (tier, status, points balance)
curl http://localhost:8080/v1/members/me \
  -H "Authorization: Bearer $TOKEN"

# Points ledger (paginated)
curl "http://localhost:8080/v1/members/me/points/ledger?page=0&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"

# Admin: update member status (ACTIVE | SUSPENDED | DELINQUENT)
curl -X PUT http://localhost:8080/v1/members/<memberId>/status \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status":"SUSPENDED"}'
```

### Coupons & Campaigns

```bash
# Validate a coupon (read-only preview — does not consume the coupon)
curl -X POST http://localhost:8080/v1/coupons/validate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "couponCode": "WELCOME10",
    "orderContext": {"subtotal": 500.00, "shippingCost": 15.00}
  }'

# Validate a campaign
curl -X POST http://localhost:8080/v1/campaigns/validate \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "campaignId": "<uuid>",
    "orderContext": {"subtotal": 1200.00, "shippingCost": 10.00}
  }'
```

Seeded coupon codes: `WELCOME10` (10% off), `SAVE50` ($50 off orders ≥ $500),
`FREESHIP` (free shipping on orders ≥ $200).

### Orders

The `Idempotency-Key` header is **required** on `POST /v1/orders`.
Sending the same key within 10 minutes returns the original order (HTTP 200).

```bash
# Place order
curl -X POST http://localhost:8080/v1/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-unique-key-001" \
  -d '{
    "buyerId": "<userId>",
    "items": [{"sku":"SKU-001","quantity":2,"unitPrice":199.99}],
    "shippingCountry": "US",
    "shippingPostalCode": "10001",
    "paymentMethod": "CARD_ON_FILE"
  }'

# Confirm payment (PLACED → CONFIRMED_PAYMENT)
curl -X POST http://localhost:8080/v1/orders/<orderId>/confirm-payment \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"paymentReference":"PAY-REF-12345"}'

# Fulfillment (CONFIRMED_PAYMENT → FULFILLMENT_RESERVED)
curl -X POST http://localhost:8080/v1/orders/<orderId>/fulfillment \
  -H "Authorization: Bearer $TOKEN"

# Confirm delivery (FULFILLMENT_RESERVED → DELIVERY_CONFIRMED)
curl -X POST http://localhost:8080/v1/orders/<orderId>/confirm-delivery \
  -H "Authorization: Bearer $TOKEN"

# Complete (DELIVERY_CONFIRMED → COMPLETED)
curl -X POST http://localhost:8080/v1/orders/<orderId>/complete \
  -H "Authorization: Bearer $TOKEN"

# Add note
curl -X POST http://localhost:8080/v1/orders/<orderId>/notes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"note":"Customer requested expedited shipping"}'

# Add attachment
curl -X POST http://localhost:8080/v1/orders/<orderId>/attachments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"blobRef":"s3://bucket/receipt.pdf","contentType":"application/pdf"}'

# List orders (optional filters: buyerId, status, orderNumber)
curl "http://localhost:8080/v1/orders?page=0&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

Seeded SKUs: `SKU-001` (100 units), `SKU-002` (50), `SKU-003` (200),
`SKU-004` (0 — out of stock), `SKU-005` (75).

State machine: `PLACED → CONFIRMED_PAYMENT → FULFILLMENT_RESERVED → DELIVERY_CONFIRMED → COMPLETED`
Invalid transitions return HTTP 409. PLACED orders not paid within 30 minutes
are auto-closed by a scheduled job (runs every 60 s).

### Work Orders

```bash
# Create
curl -X POST http://localhost:8080/v1/work-orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"description":"Device screen needs replacement","attachments":[]}'

# Get work order
curl http://localhost:8080/v1/work-orders/<workOrderId> \
  -H "Authorization: Bearer $TOKEN"

# Claim (technician assigns themselves — uses pessimistic lock to prevent double-claim)
curl -X POST http://localhost:8080/v1/work-orders/<workOrderId>/claim \
  -H "Authorization: Bearer $TOKEN"

# Add event
curl -X POST http://localhost:8080/v1/work-orders/<workOrderId>/events \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"eventType":"FIRST_RESPONSE_SENT","payload":"Initial diagnosis complete"}'

# Update cost (locked once RESOLUTION_CONFIRMED is fired)
curl -X PUT http://localhost:8080/v1/work-orders/<workOrderId>/cost \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"partsCost":150.00,"laborCost":80.00,"notes":"Screen + labor"}'

# Submit rating (transitions RESOLVED → CLOSED)
curl -X POST http://localhost:8080/v1/work-orders/<workOrderId>/rating \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"rating":5,"comment":"Excellent service!"}'

# Analytics
curl "http://localhost:8080/v1/work-orders/analytics?from=2025-01-01&to=2025-12-31" \
  -H "Authorization: Bearer $TOKEN"

# Export CSV
curl "http://localhost:8080/v1/work-orders/export?format=csv" \
  -H "Authorization: Bearer $TOKEN" -o work_orders.csv
```

SLA timers are computed from `operational_params`:
- `sla_first_response_hours` (default 4) — business hours
- `sla_resolution_days` (default 3) — business days
- `business_hours_start` / `business_hours_end` — configurable window
- `business_holidays` — JSON array of ISO-8601 dates to exclude

### Projects & Outcomes

```bash
# Create project
curl -X POST http://localhost:8080/v1/projects \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"AI Research 2025","description":"NLP and vision research"}'

# Get project
curl http://localhost:8080/v1/projects/<projectId> \
  -H "Authorization: Bearer $TOKEN"

# Register outcome (contributions MUST sum to exactly 100%)
curl -X POST http://localhost:8080/v1/outcomes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "PAPER",
    "title": "Attention Mechanisms in Large Language Models",
    "abstractText": "We present a novel analysis of attention...",
    "projectId": "<projectId>",
    "certificateNumber": "PAPER-2025-001",
    "contributions": [
      {"contributorId": "<userId1>", "sharePercent": 60.0},
      {"contributorId": "<userId2>", "sharePercent": 40.0}
    ],
    "evidences": [{"evidenceType":"PDF","blobRef":"s3://bucket/paper.pdf"}]
  }'

# Get contributions for an outcome
curl http://localhost:8080/v1/outcomes/<outcomeId>/contributions \
  -H "Authorization: Bearer $TOKEN"

# Pre-check for duplicates before submission
curl -X POST http://localhost:8080/v1/outcomes/duplicates/check \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"PAPER","title":"Attention Mechanisms","contributions":[{"contributorId":"<id>","sharePercent":100}]}'

# Get outcome
curl http://localhost:8080/v1/outcomes/<outcomeId> \
  -H "Authorization: Bearer $TOKEN"

# List by project (paginated)
curl "http://localhost:8080/v1/projects/<projectId>/outcomes?page=0&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

Duplicate detection routes outcomes to `UNDER_REVIEW` status + moderation queue
when: exact normalized title match OR Jaccard token overlap ≥ 70% on abstract text.
Certificate numbers must be globally unique.

### Interactions

```bash
# Post comment (rate-limited: 30/hour; sensitive words → PENDING + moderation queue)
curl -X POST http://localhost:8080/v1/interactions/comments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contentType":"OUTCOME","contentId":"<id>","text":"Great research!"}'

# List comments (paginated)
curl "http://localhost:8080/v1/interactions/comments?contentType=OUTCOME&contentId=<id>&page=0&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"

# Like (idempotent)
curl -X POST http://localhost:8080/v1/interactions/likes \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contentType":"OUTCOME","contentId":"<id>"}'

# Favorite (idempotent)
curl -X POST http://localhost:8080/v1/interactions/favorites \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contentType":"OUTCOME","contentId":"<id>"}'

# Report (rate-limited: 10/day)
curl -X POST http://localhost:8080/v1/interactions/reports \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"contentType":"COMMENT","contentId":"<id>","reason":"Off-topic"}'
```

Rate limit exceeded returns HTTP 429 with a `Retry-After` header (seconds).
Blacklisted users receive HTTP 403 on comment attempts.

### Admin

```bash
# KPIs
curl http://localhost:8080/v1/admin/kpis \
  -H "Authorization: Bearer $TOKEN"

# Audit log (filterable by actorUserId, resourceType, from/to, page)
curl "http://localhost:8080/v1/admin/audit-events?page=0&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"

# Get / set operational params
curl http://localhost:8080/v1/admin/operational-params/sla_first_response_hours \
  -H "Authorization: Bearer $TOKEN"

curl -X POST http://localhost:8080/v1/admin/operational-params \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"key":"sensitive_words","valueJson":"[\"spam\",\"abuse\",\"scam\"]"}'

# Add holiday (excludes date from SLA calculations)
curl -X POST http://localhost:8080/v1/admin/operational-params \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"key":"business_holidays","valueJson":"[\"2025-12-25\",\"2026-01-01\"]"}'

# Export orders (CSV or Excel)
curl "http://localhost:8080/v1/admin/exports/orders?format=csv" \
  -H "Authorization: Bearer $TOKEN" -o orders.csv

curl "http://localhost:8080/v1/admin/exports/orders?format=excel" \
  -H "Authorization: Bearer $TOKEN" -o orders.xlsx

# Export work orders
curl "http://localhost:8080/v1/admin/exports/work-orders?format=csv" \
  -H "Authorization: Bearer $TOKEN" -o work_orders.csv
```

### Health

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

## Project Structure

```
repo/
├── src/main/java/com/example/ricms/
│   ├── config/          AppProperties, JacksonConfig
│   ├── controller/      REST controllers
│   ├── domain/          JPA entities + enums
│   ├── dto/             Request/response DTOs
│   ├── exception/       AppException, RateLimitException, GlobalExceptionHandler
│   ├── repository/      Spring Data JPA repositories
│   ├── scheduler/       OrderTimeoutScheduler, MemberTierScheduler
│   ├── security/        JWT filter, PermissionEnforcer, SecurityConfig
│   └── service/         Business logic
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/    Flyway migrations (V1–V7)
├── unit_tests/           JUnit 5 unit tests (Maven adds as test source root)
├── API_tests/            curl-based functional test scripts
├── run_tests.sh          Unified test runner
├── Dockerfile
├── docker-compose.yml
├── .env.example          Environment variable template (copy to .env)
└── .gitignore
```

---

## Key Business Rules

| Rule | Implementation |
|------|---------------|
| Idempotency-Key required on order placement (Q8) | 400 if header absent; 10-min SHA-256 hash window |
| All-or-nothing inventory (Q9) | Two-pass check-then-reserve in same TX |
| Pricing priority: member → threshold coupon → pct coupon → shipping waiver (Q6) | PricingEngine |
| 1 point per $1 after discounts, floor (Q4) | `setScale(0, FLOOR)` |
| Immediate tier upgrade; monthly-only downgrade (Q5) | `checkAndUpgradeTier()` + `@Scheduled` |
| SLA respects business hours + holidays (Q10) | SlaService reads `operational_params` |
| Cost locked on RESOLUTION_CONFIRMED (Q12) | Auto-sets `lockedAt` in `addEvent()` |
| Contributions must sum to 100% (Q13) | Validated before any DB write |
| Duplicate detection at 70% abstract overlap (Q14) | Jaccard token similarity |
| Rate limits: 429 + Retry-After (Q15) | `RateLimitException` → `GlobalExceptionHandler` |
