# Self-Test Fix Report (Round 1 → Round 2)

## Issue 1 (High): Invalid encryption key in `.env.example`

**What was fixed:**
- Replaced the non-Base64 placeholder `CHANGE_ME_GENERATE_WITH_openssl_rand_base64_32` with the valid development key `WnVea3WTlDC0CXcZaL/SDRVSisv4xPnyM1Ja8VFoGWo=` (matches the `docker-compose.yml` default fallback).
- Added explicit documentation that the key must be Base64-encoded 32-byte.
- Updated README quick-start section to note the template ships with a working dev key and production requires regeneration.

**File:line references:**
- `.env.example:12-15`
- `README.md:11-13`
- `docker-compose.yml:37` (already had valid default — no change needed)

**Tests added/updated:** None required (startup validation in `EncryptedStringConverter` already covers invalid key rejection; `EncryptedStringConverterTest` tests key length validation).

---

## Issue 2 (High): Missing object-level write authorization for work-order mutations

**What was fixed:**
- Added `assertWorkOrderWriteAccess(WorkOrder wo, UUID actorId)` method to `WorkOrderService`. The check enforces that the caller is either the assigned technician or holds `ADMIN:WRITE`.
- Added the check to `addEvent()`, `updateCost()`, and `submitRating()`.
- `claimWorkOrder()` was not gated because it self-assigns (no prior technician to check against); `createWorkOrder()` is a new entity creation — no ownership to assert yet.

**File:line references:**
- `src/main/java/com/example/ricms/service/WorkOrderService.java:51-60` (new helper)
- `src/main/java/com/example/ricms/service/WorkOrderService.java:139` (addEvent guard)
- `src/main/java/com/example/ricms/service/WorkOrderService.java:182` (updateCost guard)
- `src/main/java/com/example/ricms/service/WorkOrderService.java:225` (submitRating guard)

**Tests added/updated:**
- `unit_tests/.../WorkOrderServiceTest.java`: Added 4 new tests:
  - `addEvent_byNonAssignedNonAdmin_throws403` — negative case
  - `addEvent_byAssignedTechnician_succeeds` — positive case
  - `updateCost_byNonAssignedNonAdmin_throws403` — negative case
  - `submitRating_byNonAssignedNonAdmin_throws403` — negative case
- Updated existing rating tests to use `technicianA` (the assigned tech) as rater, so they pass the new authorization check.
- Added `SecurityContextHolder` setup/teardown to `WorkOrderServiceTest`.

---

## Issue 3 (High): Idempotency lookup risk after DB uniqueness removal

**What was fixed:**
- Changed `OrderRepository` method from `findByBuyerUserIdAndIdempotencyKeyHash` (which returns `Optional` and can throw `NonUniqueResultException` with multiple rows) to `findTopByBuyerUserIdAndIdempotencyKeyHashOrderByCreatedAtDesc` which deterministically fetches only the most recent matching order.
- Updated `OrderService.placeOrder()` to call the new method.
- The 10-minute replay window check then runs against the latest row, which is the correct semantic.

**File:line references:**
- `src/main/java/com/example/ricms/repository/OrderRepository.java:22-27` (new method signature + Javadoc)
- `src/main/java/com/example/ricms/service/OrderService.java:77-78` (call site updated)

**Tests added/updated:**
- `unit_tests/.../OrderServiceTest.java`: Updated all mocks from `findByBuyerUserIdAndIdempotencyKeyHash` → `findTopByBuyerUserIdAndIdempotencyKeyHashOrderByCreatedAtDesc`.
- Added `placeOrder_multipleHistoricalRowsSameHash_returnsLatest` — verifies that when the repo returns the latest matching order within window, it correctly replays.

---

## Issue 4 (Medium): Work-order export format inconsistency

**What was fixed:**
- The `WorkOrderController.exportWorkOrders()` endpoint now rejects `format=excel` with HTTP 400 and a message pointing users to the admin endpoint (`/v1/admin/exports/work-orders?format=excel`) which already has a real Apache POI Excel implementation.
- This prevents mislabeled CSV bytes from being served with an Excel content-type.
- Updated README to document that this endpoint only supports CSV.

**File:line references:**
- `src/main/java/com/example/ricms/controller/WorkOrderController.java:81-97`
- `README.md:284` (documentation note)

**Tests added/updated:** The admin export endpoint (`AdminService.exportWorkOrders("excel")`) already has a proper POI-based implementation — no test changes needed. The endpoint-level rejection is straightforward (400 on unsupported format).

---

## Issue 5 (Medium): API test / controller status code misalignment

**What was fixed:**
- Controllers return `201 CREATED` for POST create endpoints. Updated API test assertions to expect 201 instead of 200.
- **`05_outcomes.sh`**: `register outcome` assertions changed from 200 → 201 (lines 42, 94).
- **`06_interactions.sh`**: `post comment`, `add like`, `duplicate like`, `add favorite`, `duplicate favorite`, `report content`, `sensitive word comment` assertions changed from 200 → 201.
- **`07_admin.sh`**: Added authenticated non-admin 403 tests for `GET /v1/admin/kpis`, `GET /v1/admin/audit-events`, and `GET /v1/admin/exports/orders`.

**File:line references:**
- `API_tests/05_outcomes.sh:42,94`
- `API_tests/06_interactions.sh:17,51,59,63,69,73,82`
- `API_tests/07_admin.sh:100-128` (new non-admin 403 section)

**Tests added/updated:**
- 9 assertion corrections across `05_outcomes.sh` and `06_interactions.sh`
- 3 new non-admin 403 assertions in `07_admin.sh`

---

## Cannot Confirm Statically

| Item | Reason |
|------|--------|
| Runtime startup with `.env.example` values | Requires Docker execution |
| Actual 403 HTTP response shape from Spring Security filter chain | Depends on `SecurityConfig` filter ordering at runtime |
| Idempotency query plan under multiple historical rows | Requires PostgreSQL + real data |
| Apache POI Excel output readability in Excel clients | Requires runtime export + manual verification |
| Scheduler timing behavior (order timeout, tier downgrade) | Requires running application with clock progression |
