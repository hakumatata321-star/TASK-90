# Test Coverage Audit

## Project Type Detection
- Declared in README: `backend` (`repo/README.md:3`).
- Inference check: backend-only structure present (`repo/src/main/java/...`, `repo/pom.xml`, shell API tests, no frontend package/test files).

## Backend Endpoint Inventory

| # | Endpoint (METHOD PATH) | Source Evidence |
|---|---|---|
| 1 | `POST /v1/auth/login` | `repo/src/main/java/com/example/ricms/controller/AuthController.java:21` (`login`) |
| 2 | `POST /v1/auth/password/rotate` | `repo/src/main/java/com/example/ricms/controller/AuthController.java:31` (`rotatePassword`) |
| 3 | `GET /v1/users` | `repo/src/main/java/com/example/ricms/controller/UserController.java:25` (`listUsers`) |
| 4 | `GET /v1/users/{userId}` | `repo/src/main/java/com/example/ricms/controller/UserController.java:34` (`getUser`) |
| 5 | `POST /v1/users` | `repo/src/main/java/com/example/ricms/controller/UserController.java:43` (`createUser`) |
| 6 | `POST /v1/users/{userId}/roles` | `repo/src/main/java/com/example/ricms/controller/UserController.java:53` (`assignRoles`) |
| 7 | `GET /v1/roles` | `repo/src/main/java/com/example/ricms/controller/RoleController.java:29` (`listRoles`) |
| 8 | `GET /v1/roles/{roleId}` | `repo/src/main/java/com/example/ricms/controller/RoleController.java:36` (`getRole`) |
| 9 | `POST /v1/roles` | `repo/src/main/java/com/example/ricms/controller/RoleController.java:41` (`createRole`) |
| 10 | `GET /v1/permissions` | `repo/src/main/java/com/example/ricms/controller/RoleController.java:51` (`listPermissions`) |
| 11 | `POST /v1/roles/{roleId}/permissions` | `repo/src/main/java/com/example/ricms/controller/RoleController.java:67` (`setPermissions`) |
| 12 | `GET /v1/members/me` | `repo/src/main/java/com/example/ricms/controller/MemberController.java:34` (`getMyProfile`) |
| 13 | `GET /v1/members/me/points/ledger` | `repo/src/main/java/com/example/ricms/controller/MemberController.java:45` (`getPointsLedger`) |
| 14 | `PUT /v1/members/{memberId}/status` | `repo/src/main/java/com/example/ricms/controller/MemberController.java:64` (`updateStatus`) |
| 15 | `POST /v1/orders` | `repo/src/main/java/com/example/ricms/controller/OrderController.java:28` (`placeOrder`) |
| 16 | `GET /v1/orders` | `repo/src/main/java/com/example/ricms/controller/OrderController.java:38` (`listOrders`) |
| 17 | `GET /v1/orders/{orderId}` | `repo/src/main/java/com/example/ricms/controller/OrderController.java:48` (`getOrder`) |
| 18 | `POST /v1/orders/{orderId}/confirm-payment` | `repo/src/main/java/com/example/ricms/controller/OrderController.java:53` (`confirmPayment`) |
| 19 | `POST /v1/orders/{orderId}/fulfillment` | `repo/src/main/java/com/example/ricms/controller/OrderController.java:60` (`fulfillOrder`) |
| 20 | `POST /v1/orders/{orderId}/confirm-delivery` | `repo/src/main/java/com/example/ricms/controller/OrderController.java:65` (`confirmDelivery`) |
| 21 | `POST /v1/orders/{orderId}/complete` | `repo/src/main/java/com/example/ricms/controller/OrderController.java:70` (`completeOrder`) |
| 22 | `POST /v1/orders/{orderId}/notes` | `repo/src/main/java/com/example/ricms/controller/OrderController.java:75` (`addNote`) |
| 23 | `POST /v1/orders/{orderId}/attachments` | `repo/src/main/java/com/example/ricms/controller/OrderController.java:83` (`addAttachment`) |
| 24 | `POST /v1/projects` | `repo/src/main/java/com/example/ricms/controller/ProjectController.java:21` (`createProject`) |
| 25 | `GET /v1/projects/{projectId}` | `repo/src/main/java/com/example/ricms/controller/ProjectController.java:26` (`getProject`) |
| 26 | `POST /v1/outcomes` | `repo/src/main/java/com/example/ricms/controller/OutcomeController.java:25` (`registerOutcome`) |
| 27 | `GET /v1/outcomes/{outcomeId}` | `repo/src/main/java/com/example/ricms/controller/OutcomeController.java:31` (`getOutcome`) |
| 28 | `POST /v1/outcomes/duplicates/check` | `repo/src/main/java/com/example/ricms/controller/OutcomeController.java:36` (`checkDuplicate`) |
| 29 | `GET /v1/outcomes/{outcomeId}/contributions` | `repo/src/main/java/com/example/ricms/controller/OutcomeController.java:41` (`listContributions`) |
| 30 | `GET /v1/projects/{projectId}/outcomes` | `repo/src/main/java/com/example/ricms/controller/OutcomeController.java:46` (`listByProject`) |
| 31 | `POST /v1/interactions/comments` | `repo/src/main/java/com/example/ricms/controller/InteractionController.java:26` (`createComment`) |
| 32 | `GET /v1/interactions/comments` | `repo/src/main/java/com/example/ricms/controller/InteractionController.java:32` (`getComments`) |
| 33 | `POST /v1/interactions/likes` | `repo/src/main/java/com/example/ricms/controller/InteractionController.java:41` (`createLike`) |
| 34 | `POST /v1/interactions/favorites` | `repo/src/main/java/com/example/ricms/controller/InteractionController.java:47` (`createFavorite`) |
| 35 | `POST /v1/interactions/reports` | `repo/src/main/java/com/example/ricms/controller/InteractionController.java:53` (`createReport`) |
| 36 | `POST /v1/campaigns/validate` | `repo/src/main/java/com/example/ricms/controller/CampaignController.java:37` (`validate`) |
| 37 | `POST /v1/coupons/validate` | `repo/src/main/java/com/example/ricms/controller/CouponController.java:33` (`validate`) |
| 38 | `POST /v1/work-orders` | `repo/src/main/java/com/example/ricms/controller/WorkOrderController.java:30` (`createWorkOrder`) |
| 39 | `GET /v1/work-orders/{workOrderId}` | `repo/src/main/java/com/example/ricms/controller/WorkOrderController.java:36` (`getWorkOrder`) |
| 40 | `POST /v1/work-orders/{workOrderId}/claim` | `repo/src/main/java/com/example/ricms/controller/WorkOrderController.java:41` (`claimWorkOrder`) |
| 41 | `POST /v1/work-orders/{workOrderId}/events` | `repo/src/main/java/com/example/ricms/controller/WorkOrderController.java:46` (`addEvent`) |
| 42 | `PUT /v1/work-orders/{workOrderId}/cost` | `repo/src/main/java/com/example/ricms/controller/WorkOrderController.java:54` (`updateCost`) |
| 43 | `POST /v1/work-orders/{workOrderId}/rating` | `repo/src/main/java/com/example/ricms/controller/WorkOrderController.java:61` (`submitRating`) |
| 44 | `GET /v1/work-orders/analytics` | `repo/src/main/java/com/example/ricms/controller/WorkOrderController.java:68` (`getAnalytics`) |
| 45 | `GET /v1/work-orders/export` | `repo/src/main/java/com/example/ricms/controller/WorkOrderController.java:81` (`exportWorkOrders`) |
| 46 | `POST /v1/admin/moderation-queue/{id}/resolve` | `repo/src/main/java/com/example/ricms/controller/AdminController.java:26` (`resolveModeration`) |
| 47 | `GET /v1/admin/kpis` | `repo/src/main/java/com/example/ricms/controller/AdminController.java:35` (`getKpis`) |
| 48 | `POST /v1/admin/operational-params` | `repo/src/main/java/com/example/ricms/controller/AdminController.java:40` (`setOperationalParam`) |
| 49 | `PUT /v1/admin/coupons/{couponId}/active` | `repo/src/main/java/com/example/ricms/controller/AdminController.java:47` (`setCouponActive`) |
| 50 | `PUT /v1/admin/campaigns/{campaignId}/active` | `repo/src/main/java/com/example/ricms/controller/AdminController.java:55` (`setCampaignActive`) |
| 51 | `GET /v1/admin/operational-params/{key}` | `repo/src/main/java/com/example/ricms/controller/AdminController.java:63` (`getOperationalParam`) |
| 52 | `GET /v1/admin/exports/{entity}` | `repo/src/main/java/com/example/ricms/controller/AdminController.java:72` (`export`) |
| 53 | `GET /v1/admin/audit-events` | `repo/src/main/java/com/example/ricms/controller/AuditController.java:31` (`listAuditEvents`) |

## API Test Mapping Table

Legend for test type:
- `true no-mock HTTP`: request goes over real HTTP via `curl` (`repo/API_tests/lib.sh:93`, `repo/API_tests/lib.sh:111`) and no application-layer mocking exists in API scripts.
- `HTTP with mocking`: none detected.
- `unit-only / indirect`: not used for endpoint coverage.

| Endpoint | Covered | Test type | Test files | Evidence |
|---|---|---|---|---|
| `POST /v1/auth/login` | yes | true no-mock HTTP | `repo/API_tests/01_auth.sh` | `api_call_noauth POST /v1/auth/login` (`01_auth.sh:10`, `01_auth.sh:19`) |
| `POST /v1/auth/password/rotate` | yes | true no-mock HTTP | `repo/API_tests/01_auth.sh` | `api_call POST /v1/auth/password/rotate` (`01_auth.sh:81`) |
| `GET /v1/users` | yes | true no-mock HTTP | `repo/API_tests/09_rbac.sh` | `api_call GET /v1/users` (`09_rbac.sh:14`) |
| `GET /v1/users/{userId}` | yes | true no-mock HTTP | `repo/API_tests/09_rbac.sh` | `api_call GET "/v1/users/$ADMIN_USER_ID"` (`09_rbac.sh:37`) |
| `POST /v1/users` | yes | true no-mock HTTP | `repo/API_tests/09_rbac.sh` | `api_call POST /v1/users ...` (`09_rbac.sh:52`) |
| `POST /v1/users/{userId}/roles` | yes | true no-mock HTTP | `repo/API_tests/09_rbac.sh`, `repo/API_tests/01_auth.sh` | `api_call POST "/v1/users/$NEW_USER_ID/roles"` (`09_rbac.sh:178`) |
| `GET /v1/roles` | yes | true no-mock HTTP | `repo/API_tests/09_rbac.sh` | `api_call GET /v1/roles` (`09_rbac.sh:81`) |
| `GET /v1/roles/{roleId}` | yes | true no-mock HTTP | `repo/API_tests/09_rbac.sh` | `api_call GET "/v1/roles/$ADMIN_ROLE_ID"` (`09_rbac.sh:101`) |
| `POST /v1/roles` | yes | true no-mock HTTP | `repo/API_tests/09_rbac.sh` | `api_call POST /v1/roles` (`09_rbac.sh:117`) |
| `GET /v1/permissions` | yes | true no-mock HTTP | `repo/API_tests/09_rbac.sh` | `api_call GET /v1/permissions` (`09_rbac.sh:137`) |
| `POST /v1/roles/{roleId}/permissions` | yes | true no-mock HTTP | `repo/API_tests/09_rbac.sh` | `api_call POST "/v1/roles/$NEW_ROLE_ID/permissions"` (`09_rbac.sh:155`) |
| `GET /v1/members/me` | yes | true no-mock HTTP | `repo/API_tests/02_members.sh`, `repo/API_tests/01_auth.sh` | `api_call GET /v1/members/me` (`02_members.sh:12`) |
| `GET /v1/members/me/points/ledger` | yes | true no-mock HTTP | `repo/API_tests/02_members.sh` | `api_call GET /v1/members/me/points/ledger` (`02_members.sh:23`) |
| `PUT /v1/members/{memberId}/status` | yes | true no-mock HTTP | `repo/API_tests/02_members.sh` | `api_call PUT "/v1/members/$ADMIN_MEMBER_ID/status" ...` (`02_members.sh:35`) |
| `POST /v1/orders` | yes | true no-mock HTTP | `repo/API_tests/03_orders.sh` | direct `curl -X POST "$BASE_URL/v1/orders"` (`03_orders.sh:38`, `03_orders.sh:54`) |
| `GET /v1/orders` | yes | true no-mock HTTP | `repo/API_tests/03_orders.sh` | `api_call GET "/v1/orders?page=0&pageSize=10"` (`03_orders.sh:67`) |
| `GET /v1/orders/{orderId}` | yes | true no-mock HTTP | `repo/API_tests/03_orders.sh` | `api_call GET "/v1/orders/$ORDER_ID"` (`03_orders.sh:74`) |
| `POST /v1/orders/{orderId}/confirm-payment` | yes | true no-mock HTTP | `repo/API_tests/03_orders.sh` | `api_call POST "/v1/orders/$ORDER_ID/confirm-payment"` (`03_orders.sh:87`) |
| `POST /v1/orders/{orderId}/fulfillment` | yes | true no-mock HTTP | `repo/API_tests/03_orders.sh` | `api_call POST "/v1/orders/$ORDER_ID/fulfillment"` (`03_orders.sh:105`) |
| `POST /v1/orders/{orderId}/confirm-delivery` | yes | true no-mock HTTP | `repo/API_tests/03_orders.sh` | `api_call POST "/v1/orders/$ORDER_ID/confirm-delivery"` (`03_orders.sh:113`) |
| `POST /v1/orders/{orderId}/complete` | yes | true no-mock HTTP | `repo/API_tests/03_orders.sh` | `api_call POST "/v1/orders/$ORDER_ID/complete"` (`03_orders.sh:121`) |
| `POST /v1/orders/{orderId}/notes` | yes | true no-mock HTTP | `repo/API_tests/03_orders.sh` | `api_call POST "/v1/orders/$ORDER_ID/notes"` (`03_orders.sh:136`) |
| `POST /v1/orders/{orderId}/attachments` | yes | true no-mock HTTP | `repo/API_tests/03_orders.sh` | `api_call POST "/v1/orders/$ORDER_ID/attachments"` (`03_orders.sh:143`) |
| `POST /v1/projects` | yes | true no-mock HTTP | `repo/API_tests/05_outcomes.sh` | direct `curl -X POST "$BASE_URL/v1/projects"` (`05_outcomes.sh:17`) |
| `GET /v1/projects/{projectId}` | yes | true no-mock HTTP | `repo/API_tests/05_outcomes.sh` | `api_call GET "/v1/projects/$PROJECT_ID"` (`05_outcomes.sh:143`) |
| `POST /v1/outcomes` | yes | true no-mock HTTP | `repo/API_tests/05_outcomes.sh` | `api_call POST /v1/outcomes ...` (`05_outcomes.sh:41`) |
| `GET /v1/outcomes/{outcomeId}` | yes | true no-mock HTTP | `repo/API_tests/05_outcomes.sh` | `api_call GET "/v1/outcomes/$OUTCOME_ID"` (`05_outcomes.sh:122`) |
| `POST /v1/outcomes/duplicates/check` | yes | true no-mock HTTP | `repo/API_tests/05_outcomes.sh` | `api_call POST /v1/outcomes/duplicates/check ...` (`05_outcomes.sh:99`) |
| `GET /v1/outcomes/{outcomeId}/contributions` | yes | true no-mock HTTP | `repo/API_tests/05_outcomes.sh` | `api_call GET "/v1/outcomes/$OUTCOME_ID/contributions"` (`05_outcomes.sh:156`) |
| `GET /v1/projects/{projectId}/outcomes` | yes | true no-mock HTTP | `repo/API_tests/05_outcomes.sh` | `api_call GET "/v1/projects/$PROJECT_ID/outcomes"` (`05_outcomes.sh:135`) |
| `POST /v1/interactions/comments` | yes | true no-mock HTTP | `repo/API_tests/06_interactions.sh` | `api_call POST /v1/interactions/comments ...` (`06_interactions.sh:15`) |
| `GET /v1/interactions/comments` | yes | true no-mock HTTP | `repo/API_tests/06_interactions.sh` | `api_call GET "/v1/interactions/comments?..."` (`06_interactions.sh:24`) |
| `POST /v1/interactions/likes` | yes | true no-mock HTTP | `repo/API_tests/06_interactions.sh` | `api_call POST /v1/interactions/likes ...` (`06_interactions.sh:57`) |
| `POST /v1/interactions/favorites` | yes | true no-mock HTTP | `repo/API_tests/06_interactions.sh` | `api_call POST /v1/interactions/favorites ...` (`06_interactions.sh:68`) |
| `POST /v1/interactions/reports` | yes | true no-mock HTTP | `repo/API_tests/06_interactions.sh` | `api_call POST /v1/interactions/reports ...` (`06_interactions.sh:80`) |
| `POST /v1/campaigns/validate` | yes | true no-mock HTTP | `repo/API_tests/04_coupons_campaigns.sh` | `api_call POST /v1/campaigns/validate ...` (`04_coupons_campaigns.sh:73`) |
| `POST /v1/coupons/validate` | yes | true no-mock HTTP | `repo/API_tests/04_coupons_campaigns.sh` | `api_call POST /v1/coupons/validate ...` (`04_coupons_campaigns.sh:15`) |
| `POST /v1/work-orders` | yes | true no-mock HTTP | `repo/API_tests/08_work_orders.sh` | `api_call POST /v1/work-orders ...` (`08_work_orders.sh:14`) |
| `GET /v1/work-orders/{workOrderId}` | yes | true no-mock HTTP | `repo/API_tests/08_work_orders.sh` | `api_call GET "/v1/work-orders/$WO_ID"` (`08_work_orders.sh:39`) |
| `POST /v1/work-orders/{workOrderId}/claim` | yes | true no-mock HTTP | `repo/API_tests/08_work_orders.sh` | `api_call POST "/v1/work-orders/$WO_ID/claim"` (`08_work_orders.sh:54`) |
| `POST /v1/work-orders/{workOrderId}/events` | yes | true no-mock HTTP | `repo/API_tests/08_work_orders.sh` | `api_call POST "/v1/work-orders/$WO_ID/events"` (`08_work_orders.sh:69`) |
| `PUT /v1/work-orders/{workOrderId}/cost` | yes | true no-mock HTTP | `repo/API_tests/08_work_orders.sh` | `api_call PUT "/v1/work-orders/$WO_ID/cost"` (`08_work_orders.sh:96`) |
| `POST /v1/work-orders/{workOrderId}/rating` | yes | true no-mock HTTP | `repo/API_tests/08_work_orders.sh` | `api_call POST "/v1/work-orders/$WO_ID/rating"` (`08_work_orders.sh:120`) |
| `GET /v1/work-orders/analytics` | yes | true no-mock HTTP | `repo/API_tests/08_work_orders.sh` | `api_call GET "/v1/work-orders/analytics?..."` (`08_work_orders.sh:149`) |
| `GET /v1/work-orders/export` | yes | true no-mock HTTP | `repo/API_tests/08_work_orders.sh` | `api_call GET "/v1/work-orders/export?format=csv"` (`08_work_orders.sh:166`) |
| `POST /v1/admin/moderation-queue/{id}/resolve` | yes | true no-mock HTTP | `repo/API_tests/07_admin.sh` | `api_call POST "/v1/admin/moderation-queue/$FAKE_ID/resolve"` (`07_admin.sh:87`) |
| `GET /v1/admin/kpis` | yes | true no-mock HTTP | `repo/API_tests/07_admin.sh` | `api_call GET /v1/admin/kpis` (`07_admin.sh:14`) |
| `POST /v1/admin/operational-params` | yes | true no-mock HTTP | `repo/API_tests/07_admin.sh`, `repo/API_tests/06_interactions.sh` | `api_call POST /v1/admin/operational-params ...` (`07_admin.sh:25`) |
| `PUT /v1/admin/coupons/{couponId}/active` | yes | true no-mock HTTP | `repo/API_tests/07_admin.sh` | `api_call PUT "/v1/admin/coupons/$FAKE_ID/active?active=false"` (`07_admin.sh:92`) |
| `PUT /v1/admin/campaigns/{campaignId}/active` | yes | true no-mock HTTP | `repo/API_tests/07_admin.sh` | `api_call PUT "/v1/admin/campaigns/$FAKE_ID/active?active=false"` (`07_admin.sh:96`) |
| `GET /v1/admin/operational-params/{key}` | yes | true no-mock HTTP | `repo/API_tests/07_admin.sh` | `api_call GET /v1/admin/operational-params/test_param` (`07_admin.sh:30`) |
| `GET /v1/admin/exports/{entity}` | yes | true no-mock HTTP | `repo/API_tests/07_admin.sh` | `api_call GET "/v1/admin/exports/orders?format=csv"` (`07_admin.sh:68`) |
| `GET /v1/admin/audit-events` | yes | true no-mock HTTP | `repo/API_tests/07_admin.sh` | `api_call GET "/v1/admin/audit-events?page=0&pageSize=5"` (`07_admin.sh:54`) |

## API Test Classification

1. **True No-Mock HTTP**
   - `repo/API_tests/01_auth.sh`
   - `repo/API_tests/02_members.sh`
   - `repo/API_tests/03_orders.sh`
   - `repo/API_tests/04_coupons_campaigns.sh`
   - `repo/API_tests/05_outcomes.sh`
   - `repo/API_tests/06_interactions.sh`
   - `repo/API_tests/07_admin.sh`
   - `repo/API_tests/08_work_orders.sh`
   - `repo/API_tests/09_rbac.sh`
   - Supporting evidence: HTTP calls built via `curl` helpers (`repo/API_tests/lib.sh:93`, `repo/API_tests/lib.sh:111`), no mocked transport/controller/service in API test layer.

2. **HTTP with Mocking**
   - None found in API test scripts.

3. **Non-HTTP (unit/integration without HTTP)**
   - `repo/unit_tests/com/example/ricms/service/PricingEngineTest.java`
   - `repo/unit_tests/com/example/ricms/service/MemberServiceTest.java`
   - `repo/unit_tests/com/example/ricms/service/OrderServiceTest.java`
   - `repo/unit_tests/com/example/ricms/service/OutcomeServiceTest.java`
   - `repo/unit_tests/com/example/ricms/service/InteractionServiceTest.java`
   - `repo/unit_tests/com/example/ricms/service/WorkOrderServiceTest.java`
   - `repo/unit_tests/com/example/ricms/service/SlaServiceTest.java`
   - `repo/unit_tests/com/example/ricms/security/EncryptedStringConverterTest.java`

## Mock Detection

### Detected mocking/stubbing
- Mockito-based stubbing is pervasive in unit tests (`@Mock`, `when`, `verify`):
  - `repo/unit_tests/com/example/ricms/service/OrderServiceTest.java:51`–`60` (repositories/services mocked), `:79`, `:83`.
  - `repo/unit_tests/com/example/ricms/service/WorkOrderServiceTest.java:53`–`60`, `:76`–`78`.
  - `repo/unit_tests/com/example/ricms/service/OutcomeServiceTest.java:43`–`49`, `:62`–`65`.
  - `repo/unit_tests/com/example/ricms/service/InteractionServiceTest.java:42`–`49`, `:68`–`70`.
  - `repo/unit_tests/com/example/ricms/service/SlaServiceTest.java:34`.
  - `repo/unit_tests/com/example/ricms/service/PricingEngineTest.java:43`–`46`, `:57`–`61`.
  - `repo/unit_tests/com/example/ricms/service/MemberServiceTest.java:40`–`43`.
- No `jest.mock`, `vi.mock`, or `sinon.stub` found in repo.

### Classification impact
- Unit tests are **not** eligible for true no-mock API coverage because service dependencies are mocked.
- API shell tests remain no-mock HTTP at transport/business-logic path level.

## Coverage Summary

- Total endpoints inventoried: **53**
- Endpoints with HTTP tests: **53**
- Endpoints with true no-mock HTTP tests: **53**
- HTTP coverage: **100.0%** (`53/53`)
- True API coverage: **100.0%** (`53/53`)

## Unit Test Summary

### Backend Unit Tests

- Test files:
  - `repo/unit_tests/com/example/ricms/service/PricingEngineTest.java`
  - `repo/unit_tests/com/example/ricms/service/MemberServiceTest.java`
  - `repo/unit_tests/com/example/ricms/service/OrderServiceTest.java`
  - `repo/unit_tests/com/example/ricms/service/OutcomeServiceTest.java`
  - `repo/unit_tests/com/example/ricms/service/InteractionServiceTest.java`
  - `repo/unit_tests/com/example/ricms/service/WorkOrderServiceTest.java`
  - `repo/unit_tests/com/example/ricms/service/SlaServiceTest.java`
  - `repo/unit_tests/com/example/ricms/security/EncryptedStringConverterTest.java`

- Modules covered:
  - **Services/engines:** `OrderService`, `WorkOrderService`, `OutcomeService`, `InteractionService`, `MemberService`, `SlaService`, `PricingEngine`.
  - **Security utility:** `EncryptedStringConverter`.
  - **Auth/guards/middleware:** indirectly via mocked `PermissionEnforcer`; no direct guard/filter tests.

- Important backend modules not unit-tested (directly):
  - Controllers (`repo/src/main/java/com/example/ricms/controller/*.java`) have no direct unit tests.
  - Core services not covered by unit tests: `AuthService`, `UserService`, `RoleService`, `ProjectService`, `AdminService`, `AuditService` (`repo/src/main/java/com/example/ricms/service/*.java`).
  - Security runtime components not directly tested: `JwtFilter`, `LoginRateLimitFilter`, `SecurityConfig`, `PermissionEnforcer`.

### Frontend Unit Tests (STRICT REQUIREMENT)

- Frontend test files detected: **NONE**.
  - Evidence: no `*.test.*` / `*.spec.*` JS/TS files found; no `package.json`; no frontend static/app source tree.
- Frameworks/tools detected: **NONE**.
- Frontend components/modules covered: **NONE**.
- Important frontend components/modules not tested: **Not applicable (no frontend codebase detected).**

**Mandatory verdict:** **Frontend unit tests: MISSING**

Strict failure rule application:
- Project type is `backend`, not `fullstack`/`web`; therefore no automatic CRITICAL GAP from frontend-test absence.

### Cross-Layer Observation
- No frontend layer detected; balance analysis across FE/BE is not applicable.

## API Observability Check

- Strong: tests usually show method/path, request payload, expected HTTP code, and selected response fields (`assert_contains`, `assert_status`) across scripts.
- Weak spots:
  - Some checks validate only status code without response-shape assertions (e.g., selected unauthorized/not-found cases).
  - One explicit expectation mismatch in comments/assertion intent: missing `Idempotency-Key` case comment says `400` but assertion expects `500` (`repo/API_tests/03_orders.sh:26`–`34`).

## Tests Check

- Success paths: broadly present for all endpoint groups (auth, members, orders, outcomes, interactions, admin, RBAC, work-orders).
- Failure/validation/auth paths: broadly present (401/403/404/409/422/400 coverage visible).
- Edge cases: present in unit tests (tier boundaries, idempotency windows, SLA calendars/holidays, duplicate detection thresholds).
- Integration boundaries: API tests are shell-level black-box HTTP; internal component interactions rely on mocked unit tests.
- `run_tests.sh` assessment:
  - Docker-based orchestration is present (`repo/run_tests.sh:63`, `:91`).
  - Local dependency requirement exists (`curl`, `python3`) (`repo/run_tests.sh:10`–`13`) → **FLAG (strict rule: not fully container-contained).**

## End-to-End Expectations

- Fullstack FE↔BE E2E expectation is not applicable (project declared backend).
- Backend API + backend unit suite provide substantial partial compensation for absence of browser/mobile E2E.

## Test Coverage Score (0–100)

**Score: 84/100**

## Score Rationale

- + Very high endpoint-level HTTP coverage (53/53).
- + API tests exercise real HTTP paths with no test-time app-layer mocks.
- + Service-level unit suite has meaningful business-rule assertions.
- - Heavy Mockito reliance in unit tests (good for unit isolation, weak for integration confidence).
- - Missing direct tests for several critical services/security runtime components.
- - Minor observability/assertion-quality inconsistencies (e.g., status expectation mismatch).

## Key Gaps

- No direct tests for `AuthService`, `AdminService`, `UserService`, `RoleService`, `ProjectService`, `AuditService`.
- No direct tests for security filter/guard pipeline (`JwtFilter`, `LoginRateLimitFilter`, `PermissionEnforcer`, `SecurityConfig`).
- `run_tests.sh` still depends on host `curl`/`python3`; not fully Docker-contained under strict environment policy.

## Confidence & Assumptions

- Confidence: **High** for endpoint inventory and HTTP mapping (controller annotations + explicit curl paths).
- Confidence: **Medium-High** for "true no-mock HTTP" classification, assuming app is started per documented flow (`repo/run_tests.sh:57` and README startup instructions).
- Assumption: only repository-visible controllers define in-scope business endpoints; framework-provided actuator endpoints were not counted in the 53 business-endpoint total.

---

# README Audit

## README Location

- Found at required path: `repo/README.md`.

## Hard Gate Evaluation

### Formatting
- PASS: structured markdown with headings, code blocks, and tables (`repo/README.md`).

### Startup Instructions (backend/fullstack requirement)
- PASS: includes `docker-compose up` command (`repo/README.md:18`, `repo/README.md:96`).

### Access Method
- PASS: URL/port and health endpoint documented (`repo/README.md:25`–`30`).

### Verification Method
- PASS: includes concrete verification via curl flows and health check (`repo/README.md:127` onward; `repo/README.md:460`–`463`).

### Environment Rules (STRICT)
- PASS: README now uses Docker-contained startup and test flow, and removed local JDK/Maven install/run path (`repo/README.md:45`–`69`).

### Demo Credentials (conditional)
- PASS: authentication exists and credentials include multiple roles (`repo/README.md:31`–`40`).

## High Priority Issues

- None.

## Medium Priority Issues

- API base URL is shown as `http://localhost:8082` in quick start/auth sections (`repo/README.md:27`, `repo/README.md:109`), but many curl examples still use `http://localhost:8080` (`repo/README.md:125` onward), creating mixed-port guidance.

## Low Priority Issues

- README is large and command-dense; some sections repeat similar invocation patterns, reducing scan efficiency for new operators.

## Hard Gate Failures

- None.

## README Verdict

**PASS**

Reason: all hard gates satisfied after Docker-contained test/startup documentation update.

---

## Final Verdicts

- **Test Coverage Audit Verdict:** **PASS WITH GAPS** (coverage breadth strong; isolation/integration-depth gaps remain).
- **README Audit Verdict:** **PASS** (hard gates satisfied; minor consistency issue remains).
