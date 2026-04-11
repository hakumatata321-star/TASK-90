# API Specification

This document defines the backend API for a Spring Boot + JPA (PostgreSQL) system.

## 1. Overview

The service is a resource-oriented HTTP API that supports:
- Authentication and user/role management (local username + password)
- Fine-grained authorization down to resource type and operation, enforced on every write
- Immutable audit events for privileged actions (including permission changes)
- Membership and marketing benefits (points, tiers, member pricing, coupons/campaigns)
- Order lifecycle management with inventory reservation, timeouts, idempotency, and state machine rules
- Work order management (SLA timers, technician assignment, parts/cost validation, ratings, analytics)
- Outcomes/IP registration (typed outcomes linked to projects, contribution shares, evidence archival, duplicate detection)
- Interaction governance (rate limits, moderation, reporting)
- Admin/analytics APIs (controlled management, KPI queries, exports)

Base URL: `/{version}`
Version: `v1`

Content types:
- Requests: `application/json`
- Responses: `application/json`

## 2. Common Concerns

### 2.1 Authentication

Most endpoints require a valid session/token.

Authentication model (pick one implementation strategy, both are described for clarity):
- `Bearer <access_token>` in `Authorization` header
- Optional refresh token behavior may be added later; core spec assumes access tokens only.

### 2.2 Authorization (RBAC + fine-grained)

Fine-grained authorization granularity:
- Resource level + operation level (Q1)
- Optional row-level filtering for ownership-based read/write access where applicable (Q1)

Authorization enforcement:
- Every privileged action checks permissions before mutating state (writes)
- Writes must execute within a transaction that includes authorization checks
- Permission changes emit immutable audit events (see Section 2.3)

Permissions model:
- `permission` = (`resource_type`, `operation`)
- Users have roles, roles have permissions

### 2.3 Immutable Audit Events

Audit events requirements:
- Append-only immutable store
- Every privileged action includes:
  - actor (user id)
  - timestamp (server time)
  - action/subject (resource and operation)
  - before/after values OR a diff representation (Q2)
  - reason code (e.g., `PERMISSION_GRANTED`, `ORDER_STATE_CHANGED`)

Audit storage size mitigation (Q2):
- Store diffs instead of full object snapshots for frequent changes
- Archive older audit partitions/tables into cold storage tables (operational process)

### 2.4 Rate Limits (Hard rejection + cooldown)

When limits are exceeded:
- Reject with `429 Too Many Requests` (Q15)
- Include a cooldown hint in response headers (e.g., `Retry-After`)
- Rate limits apply per user id (or per authenticated identity)

Example defaults (from prompt):
- Comments: `30 per hour per user`
- Reports: `10 per day per user`

### 2.5 Error Model

Error response shape:
```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Human readable message",
    "details": { }
  }
}
```

Common HTTP codes:
- `400` invalid input
- `401` unauthenticated
- `403` authenticated but unauthorized (missing permission)
- `404` not found
- `409` conflict (duplicate, invalid state transition)
- `422` semantic validation error
- `429` rate limited (Q15)

## 3. Authentication & User/Role Management

### 3.1 Login
`POST /v1/auth/login`

Request:
```json
{ "username": "string", "password": "string" }
```

Response 200:
```json
{ "accessToken": "string", "expiresInSeconds": 3600 }
```

### 3.2 Rotate Password (self)
`POST /v1/auth/password/rotate`
Requires permission: `AUTH_USER_SELF:WRITE`

Request:
```json
{ "currentPassword": "string", "newPassword": "string" }
```

### 3.3 User List
`GET /v1/users`
Admin-only.

Query params:
- `page`, `pageSize`
- `q` (saved search term)

### 3.4 User Roles
`POST /v1/users/{userId}/roles`
Admin-only, permission required: `RBAC_USER_ROLES:WRITE`

Request:
```json
{ "roleIds": ["string"] }
```

Audit:
- Permission and role assignment changes emit `audit_events` (immutable) (Q1/Q2)

## 4. Membership & Marketing

### 4.1 Membership Profile
`GET /v1/members/me`

Response:
```json
{
  "memberId": "string",
  "status": "ACTIVE|SUSPENDED|DELINQUENT",
  "tier": "BRONZE|SILVER|GOLD",
  "pointsBalance": 1234
}
```

Good standing definition (Q3):
- Apply member pricing only when `status = ACTIVE`.

### 4.2 Points Ledger (append-only)
`GET /v1/members/me/points/ledger`
Query params: `page`, `pageSize`

Points accrual rule (Q4):
- Points accrue at `1 point per $1.00` of eligible order subtotal
- Points are calculated based on final payable subtotal after discounts (Q4)

Implementation note:
- Use `points_ledger` with entries generated during order completion / payment confirmation.

### 4.3 Tier Upgrade/Downgrade Timing
Tier rules:
- Bronze: 0-999 points
- Silver: 1000-4999 points
- Gold: 5000+ points

Timing (Q5):
- Upgrade immediately on threshold crossing
- Downgrade on a monthly scheduled check

### 4.4 Coupons
Coupons:
- Threshold discounts: `$10 off $50` type examples
- Percentage discounts: max `$25`
- Shipping waivers

`POST /v1/coupons/validate`

Request:
```json
{
  "couponCode": "string",
  "orderContext": {
    "subtotal": 0,
    "shippingCost": 0,
    "buyerId": "string"
  }
}
```

Response:
```json
{ "isValid": true, "discount": { "amount": 0, "currency": "USD" } }
```

### 4.5 Campaigns
Campaign types:
- Second-item discounts
- Spend-and-get rewards

Campaign validation:
`POST /v1/campaigns/validate`

### 4.6 Coupon + Campaign Conflict Resolution
Stacking rules:
- Allow at most one coupon and at most one campaign per order
- No stacking conflicts (Q6)

Priority (Q6):
1. Member price
2. Fixed-amount coupon
3. Percent coupon
4. Shipping coupon

Rule (Q6):
- If multiple campaigns are eligible, apply highest priority campaign and reject others.

Endpoint that finalizes pricing should enforce this at order-creation time.

## 5. Orders & Transaction Lifecycle

### 5.1 Place Order (idempotent)
`POST /v1/orders`

Headers:
- `Idempotency-Key: <string>` (Q8)

Idempotency scope (Q8):
- Unique per buyer per 10 minutes
- Duplicate order definition: same idempotency key (Q8)

Request (example):
```json
{
  "buyerId": "string",
  "items": [
    { "sku": "string", "quantity": 2, "unitPrice": 10.00 }
  ],
  "shippingAddress": { "country": "string", "postalCode": "string" },
  "couponCode": "optional string",
  "campaignId": "optional string",
  "paymentMethod": "CASH_ON_DELIVERY|CARD_ON_FILE"
}
```

Response:
```json
{ "orderId": "string", "orderNumber": "string", "status": "PLACED" }
```

Inventory reservation and timeout (Q7):
- Reserve inventory for 30 minutes after placement.
- Orders auto-close on timeout at 30 minutes if unpaid/unconfirmed (Q7).
- On timeout, release reserved stock and update order status to `CLOSED` (Q7).

Partial fulfillment support (Q9):
- Default: all-or-nothing fulfillment required.
- If a future feature is enabled, it must be explicitly modeled; current spec enforces full completion.
  - Therefore fulfillment state transitions require all items to be fulfilled together.

### 5.2 Get Order List / Detail
`GET /v1/orders`
Query params: `page`, `pageSize`, `q`, `status`, `orderNumber`, `trackingNumber`

`GET /v1/orders/{orderId}`

### 5.3 Internal Notes and Proof Attachments
Internal notes:
`POST /v1/orders/{orderId}/notes`

Attachments (proof):
`POST /v1/orders/{orderId}/attachments`

### 5.4 Order State Machine

States (typical):
- `PLACED`
- `CONFIRMED_PAYMENT` (internal ledger entry or COD confirmation)
- `FULFILLMENT_RESERVED`
- `DELIVERY_CONFIRMED`
- `COMPLETED`
- `CLOSED` (timeout or cancellation)

SLA/timeouts:
- `PLACED` inventory reservation: 30 minutes (Q7)

State transition endpoint examples:
`POST /v1/orders/{orderId}/confirm-payment`

`POST /v1/orders/{orderId}/fulfillment`

`POST /v1/orders/{orderId}/confirm-delivery`

Idempotency for state transitions (recommended):
- Use idempotency for payment confirmation and fulfillment steps.

### 5.5 Pagination and Search
All list endpoints support:
- Cursor or page-based pagination
- Saved search query param `q`

Indexes (implementation guidance from prompt):
- `order_number`, `tracking_number`, masked display `phone`, `status`, `created_at`

## 6. Work Orders (Repair Services)

### 6.1 Create Work Order
`POST /v1/work-orders`

Request:
```json
{
  "orderId": "string",
  "description": "string",
  "attachments": [ { "type": "image|video", "blobRef": "string" } ],
  "requestedSlaTier": "optional"
}
```

### 6.2 Technician Claim / Assignment Conflicts
Claim endpoint:
`POST /v1/work-orders/{workOrderId}/claim`

Conflict rule (Q11):
- Prevent multiple technicians from claiming the same work order concurrently.
- Use locking/unique assignment constraints so only one active assignment exists.

### 6.3 Work Order Status Progression and SLA Timers
Default SLA timers (prompt):
- First response: 4 business hours
- Resolution: 3 business days

Business hours definition (Q10):
- Business hours configurable per system
- Exclude weekends and holidays

Endpoints:
`POST /v1/work-orders/{workOrderId}/events`

Events:
- `FIRST_RESPONSE_SENT`
- `STATUS_UPDATED`
- `RESOLUTION_CONFIRMED`

SLA computation:
- Timers start based on defined state transition rules.

### 6.4 Parts / Cost Validation
Cost edit endpoint:
`PUT /v1/work-orders/{workOrderId}/cost`

Validation rule (Q12):
- Parts/costs editable only in early states
- Lock edits after approval/finalization (Q12)

### 6.5 Follow-up Ratings
`POST /v1/work-orders/{workOrderId}/rating`

Request:
```json
{ "rating": 1, "comment": "optional string" }
```

### 6.6 Work Order Analytics
`GET /v1/work-orders/analytics`
Query params: date ranges, technician id, status filters.

CSV/Excel export (admin/authorized):
`GET /v1/work-orders/export?format=csv`

## 7. Outcomes / IP Registration

### 7.1 Register Outcome
`POST /v1/outcomes`

Request:
```json
{
  "type": "PAPER|PATENT|COMPETITION|SOFTWARE_COPYRIGHT",
  "title": "string",
  "abstract": "string",
  "certificateNumber": "optional string",
  "projectId": "string",
  "evidence": [ { "type": "document|image|video", "blobRef": "string" } ],
  "contributions": [
    { "contributorId": "string", "sharePercent": 40 },
    { "contributorId": "string", "sharePercent": 60 }
  ]
}
```

Contribution share validation (Q13):
- Shares must sum to exactly 100% (Q13)
- Validation enforced transactionally.

### 7.2 Duplicate Detection Accuracy
Duplicate rules (Q14):
- Certificate number uniqueness when provided (hard uniqueness constraint recommended)
- Normalized title used for candidate detection
- Abstract similarity uses token overlap threshold:
  - Start with `70%` overlap threshold (Q14)
  - Send matches to a manual review queue rather than auto-merge

Endpoints:
`POST /v1/outcomes/duplicates/check`

### 7.3 Link Outcomes to Projects
`GET /v1/projects/{projectId}/outcomes`

## 8. Interaction Governance (Comments, Likes, Favorites, Reports)

### 8.1 Comments
Create:
`POST /v1/interactions/comments`

Read:
`GET /v1/interactions/comments?contentType=ORDER|WORK_ORDER|OUTCOME&id=...`

Rate limits:
- Comments: `30 per hour per user` (Q15)
- Enforce hard rejection with cooldown (Q15)

### 8.2 Likes / Favorites
`POST /v1/interactions/likes`
`POST /v1/interactions/favorites`

### 8.3 Reports
`POST /v1/interactions/reports`

Rate limits:
- Reports: `10 per day per user` (Q15)

Moderation:
- Local sensitive-word dictionary
- Moderation queues processed by admins
- Admin enforcement actions recorded in audit events.

### 8.4 Blacklist
Local blacklist support:
- If user is blacklisted, reject interaction creation endpoints with `403` or `422` (implementation-defined).

## 9. Admin / Analytics APIs

Admin capabilities (authorization required for all endpoints):
- Controlled management of users, roles, orders, funds, reviews/complaints
- Configurable operational parameters:
  - categories, fees, bid increments, deposit policies, SLA hours
- KPI queries
- CSV/Excel export generated locally

Endpoints (high level):
- `GET /v1/admin/kpis`
- `POST /v1/admin/operational-params`
- `GET /v1/admin/exports/{entity}?format=csv|excel`

## 10. Data Export and Reporting

Exports:
- Must be generated locally (no third-party services)
- Admin/authorized only
- Provide synchronous small exports or asynchronous job endpoints for larger exports.

## 11. Summary of Question -> Spec Mapping

1. Q1: Fine-grained authorization -> resource+operation + optional row-level filtering
2. Q2: Audit bloat -> store diffs + archive old audit partitions
3. Q3: Good standing -> member pricing only when `status=ACTIVE`
4. Q4: Points timing -> based on final payable subtotal after discounts
5. Q5: Tier timing -> upgrade immediately, downgrade monthly
6. Q6: Coupon/campaign conflict -> explicit priority, max one coupon + one campaign
7. Q7: Order timeout -> release inventory and close order after 30 minutes
8. Q8: Idempotency -> unique per buyer per 10 min; duplicate defined by idempotency key
9. Q9: Partial fulfillment -> default all-or-nothing
10. Q10: Work order SLA hours -> configurable business hours exclude weekends/holidays
11. Q11: Technician conflicts -> single active claim enforced with locking/constraint
12. Q12: Cost edits -> only in early states; lock after approval/finalization
13. Q13: Contribution shares -> transactional validation sum exactly 100%
14. Q14: Outcome duplicates -> 70% token overlap threshold; manual review queue
15. Q15: Interaction limits -> hard rejection with cooldown and 429 responses

