# System Design

This document describes the architecture and key workflows for the system described in `docs/prompt.md` and the business-rule clarifications captured in `docs/questions.md`.

## 1. Project Structure (recommended)

Based on the folder layout you referenced, a typical structure for this task is:

```text
TASK-90/
  docs/
    design.md
    api-spec.md
    questions.md
  repo/
    (backend project code goes here)
```

In this workspace, only `docs/` exists so far; the API/design docs are the deliverables requested for now.

## 2. High-Level Architecture

Technology (from prompt):
- Backend: Spring Boot + JPA
- Database: PostgreSQL
- Containerization: single Docker-deployable application
- Persistence and domain validation: transactional services

Major subsystems (bounded contexts):
- Identity/RBAC: authentication, roles/permissions, authorization checks
- Audit Trail: immutable append-only audit events store
- Membership/Marketing: member profiles, points ledger, tiers, coupons and campaigns
- Ordering: order state machine, inventory reservation and timeouts, idempotency
- Work Orders: repair submission, technician claim, SLA timers, cost validation, ratings
- Outcomes/IP: typed outcomes, projects, contribution shares, evidence archiving, duplicates
- Interaction Governance: comments/likes/favorites/reports, moderation and local blacklist
- Admin/Analytics: parameter management, KPI queries, exports

Cross-cutting concerns:
- Security: bcrypt password hashing, encryption at rest for sensitive fields
- Authorization: permission checks on every write; optional row-level filters for reads
- Observability: structured logs + error traces suitable for on-machine troubleshooting

## 3. Data Model (entities and relationships)

Notes:
- This is a design-level model; table/column names can be adjusted.
- Use foreign keys and indexes guided by prompt requirements.

Core tables:
- `users` (id, username, password_hash, status, created_at)
- `roles` (id, name)
- `permissions` (id, resource_type, operation)
- `user_roles` (user_id, role_id)
- `role_permissions` (role_id, permission_id)

Audit:
- `audit_events` (id, actor_user_id, subject_resource_type, subject_id, operation, timestamp, reason_code, diff_payload_jsonb)
  - Append-only
  - Diff payload stored as JSONB (Q2)

Membership & marketing:
- `members` (member_id, user_id, status, tier, good_standing_since, created_at)
- `points_ledger` (id, member_id, points_delta, source_order_id, currency_amount_basis, created_at)
- `orders` and `order_items` (see Ordering)
- `coupons` (id, code, type, params, priority, active_from/to)
- `campaigns` (id, type, params, priority, active_from/to)

Ordering:
- `orders` (id, order_number, buyer_user_id, status, subtotal, discounts_total, shipping_total, total_payable, created_at, closed_at, payment_confirmed_at, idempotency_key_hash)
- `order_items` (id, order_id, sku, quantity, unit_price, line_total)
- `inventory` (sku, on_hand, reserved, reserved_until, updated_at)
- `attachments` (id, owner_type, owner_id, content_type, blob_ref, created_at)

Work orders:
- `work_orders` (id, work_order_number, order_id, technician_user_id nullable, status, sla_first_response_due_at, sla_resolution_due_at, approved_at, created_at)
- `work_order_events` (id, work_order_id, event_type, payload_jsonb, created_at)
- `work_order_costs` (work_order_id, approved_status, parts_cost, labor_cost, notes, locked_at nullable)

Outcomes/IP:
- `projects` (id, name, created_at)
- `outcomes` (id, type, project_id, title_normalized, title_original, abstract_text, certificate_number nullable unique, created_at)
- `contributions` (id, outcome_id, contributor_user_id, share_percent)
- `outcome_evidence` (id, outcome_id, evidence_type, blob_ref, created_at)

Interaction governance:
- `comments` (id, author_user_id, content_type, content_id, text, status, created_at)
- `likes` (id, user_id, content_type, content_id, created_at)
- `favorites` (id, user_id, content_type, content_id, created_at)
- `reports` (id, reporter_user_id, content_type, content_id, reason, status, created_at)
- `moderation_queue` (id, queue_type, target_type, target_id, payload_jsonb, created_at)
- `blacklist` (id, user_id, reason, created_at, expires_at nullable)

Operational parameter storage (admin configurable):
- `operational_params` (key, value_jsonb, updated_at)

Indexes (implementation guidance):
- `orders`: `order_number`, `tracking_number`, masked display `phone`, `status`, `created_at`
- `comments`: `(content_type, content_id, created_at)`
- `outcomes`: `title_normalized`, `certificate_number` (when provided)

## 4. Authorization & Auditing Design

### 4.1 Fine-grained authorization enforcement (Q1)

Policy:
- Before any write, the service checks a permission: (`resource_type`, `operation`).
- For ownership-based logic, implement optional row-level checks:
  - Example: user can modify their own record if `(subject.user_id == actor.user_id)`.

Implementation sketch:
- Authorization in application layer via a centralized `AuthorizationService`.
- For reads that require row-level filters, optionally incorporate filters into repository queries.

### 4.2 Immutable audit events and diff storage (Q2)

Audit event creation:
- All privileged actions must call `AuditService.record(...)`.
- Store diffs instead of full snapshots:
  - compute diff between previous and new values
  - store a JSONB diff payload (e.g., changed fields map)

Archival:
- Use time-based partitioning (or separate tables) and move older partitions to cold retention.

## 5. Ordering Workflows

### 5.1 Idempotency (Q8)

Header:
- `Idempotency-Key` required for `POST /v1/orders` (and recommended for critical state transitions).

Storage:
- Store a hashed idempotency key in `orders.idempotency_key_hash` with a per-buyer uniqueness strategy.

TTL rule (Q8):
- idempotency key validity is 10 minutes
- enforce uniqueness only within that window (via database constraint + cleanup job or by using an idempotency_keys table with expiration)

Duplicate definition (Q8):
- Duplicate order is defined by matching `buyer_id + idempotency_key`.

### 5.2 Inventory reservation and timeout close (Q7)

On order placement:
- Create `orders` row in `PLACED`
- Reserve inventory for each `order_item.sku`:
  - increment `inventory.reserved`
  - set `inventory.reserved_until = now + 30 minutes`

Timeout job:
- Runs periodically (e.g., every minute):
  - Find orders in `PLACED` (or unpaid/unconfirmed) where `now >= created_at + 30 minutes`
  - Release inventory reservations for those orders
  - Update order status to `CLOSED` (Q7)

Release behavior (Q7):
- Inventory must be released even if partial steps occurred; ensure idempotent release.

### 5.3 Partial fulfillment default (Q9)

Rule (Q9):
- Enforce all-or-nothing fulfillment.

Design implication:
- Keep fulfillment progress at order level:
  - an order can only move to `FULFILLMENT_RESERVED` when all items are reservable
  - delivery confirmation requires full item fulfillment

If partial fulfillment is added later:
- Add explicit flags and new state paths; do not silently change current behavior.

## 6. Membership & Marketing Workflows

### 6.1 Good standing (Q3)

Member pricing eligibility:
- Only when `members.status = ACTIVE`.
- If status changes, member pricing must re-evaluate at pricing time (do not retroactively apply without explicit rule).

### 6.2 Points accrual timing (Q4)

Points calculation basis (Q4):
- Points are computed from the final payable subtotal after applying discounts/campaigns (not pre-discount prices).

Ledger generation:
- Generate `points_ledger` entries when payment is confirmed and order reaches the point-accrual boundary (typically at `CONFIRMED_PAYMENT` or `COMPLETED` depending on business definition).

### 6.3 Tier upgrade and downgrade timing (Q5)

Upgrade (Q5):
- When points cross tier thresholds, update tier immediately.

Downgrade (Q5):
- Run scheduled monthly job that recalculates tier based on current points.

## 7. Coupons and Campaigns

Conflict resolution (Q6):
- Only one coupon and one campaign per order.
- If multiple campaigns apply, choose highest `priority`.

Priority order (Q6):
1. Member price
2. Fixed-amount coupon
3. Percent coupon (cap percent discount at max amount)
4. Shipping coupon

Design note:
- Implement a deterministic pricing engine:
  - validate inputs
  - compute eligibility
  - select the single best coupon and campaign
  - compute discounts
  - compute payable subtotal used for points accrual (ties back to Q4)

## 8. Work Order Workflows

### 8.1 Technician claim conflicts (Q11)

Claim design (Q11):
- Allow only one active technician assignment for a given `work_order`.

Enforcement mechanisms:
- Prefer database-level enforcement:
  - a unique constraint / row-level locking on claim state
- Use transaction isolation to prevent double claims.

### 8.2 SLA timers and business hours definition (Q10)

Business hours (Q10):
- Configurable schedule:
  - working hours windows
  - exclude weekends
  - exclude holidays

Timer computation:
- For due dates (`sla_first_response_due_at`, `sla_resolution_due_at`), compute using business-hour calendar rather than simple duration.

Event processing:
- When an event indicates first response/resolution, close/stop SLA timers and record in `work_order_events`.

### 8.3 Cost validation and edit locking (Q12)

Rule (Q12):
- Parts/costs editable only in early states.
- After approval/finalization, lock edits.

Design:
- `work_order_costs.locked_at` set when approved.
- Cost update endpoint checks:
  - work order status
  - `locked_at IS NULL`

## 9. Outcomes / IP Registration Workflows

### 9.1 Contribution shares sum validation (Q13)

Rule (Q13):
- Contribution `share_percent` values must sum to exactly 100.

Transactional enforcement:
- Validate within the same DB transaction as outcome creation.
- Reject requests that fail the sum.

### 9.2 Duplicate detection and thresholding (Q14)

Duplicate detection inputs:
- Normalized title
- certificate number uniqueness (when present)
- abstract token overlap similarity

Similarity (Q14):
- Start with `70%` token overlap threshold.

Queue behavior:
- If similarity exceeds threshold, do not auto-merge.
- Add to manual review queue with candidate set.

## 10. Interaction Governance

Rate limiting enforcement (Q15):
- Use a centralized rate limiting component keyed by user and route/action type.
- When exceeded:
  - hard reject with `429`
  - include retry/cooldown metadata (`Retry-After`)

Moderation:
- Sensitive word dictionary for content ingestion
- Moderation queue for flagged content
- Admin actions recorded via `audit_events`

Blacklist:
- Block blacklisted users from creating interactions.

## 11. Non-Functional Requirements

Performance:
- Target p95 read latency < 300 ms for common queries on 100k orders.
- Use appropriate indexes, avoid N+1 queries (JPA fetch strategy), and precompute aggregates where needed.

Security:
- bcrypt for password hashing
- encryption at rest for sensitive fields at DB level or application-managed crypto
- no third-party logins, no external email/SMS, no cloud storage, no external APIs

Deployment:
- Single Docker deployable app

Observability:
- Structured logs
- local audit/error traces for troubleshooting

## 12. Question -> Design Mapping (high level)

1. Q1: RBAC granularity -> (`resource_type`, `operation`) + optional row filters
2. Q2: Audit storage size -> diff payloads + archival strategy
3. Q3: Good standing -> `status=ACTIVE` gates member pricing
4. Q4: Points timing -> final payable subtotal after discounts
5. Q5: Tier timing -> immediate upgrades + monthly downgrades
6. Q6: Coupon/campaign conflicts -> priority selection + max one coupon + one campaign
7. Q7: Order timeout -> 30 min auto-close + release reserved inventory
8. Q8: Idempotency -> per buyer per 10 min; duplicate defined by Idempotency-Key
9. Q9: Partial fulfillment -> enforce all-or-nothing
10. Q10: SLA business hours -> configurable calendar excluding weekends/holidays
11. Q11: Technician conflicts -> single active claim enforced by transaction/constraint
12. Q12: Cost validation -> lock edits after approval/finalization
13. Q13: Contribution shares -> sum exactly 100% validated transactionally
14. Q14: Duplicate outcomes -> 70% token overlap threshold + manual review
15. Q15: Interaction limits -> hard rejection with cooldown and `429`

