# Business Logic Questions Log

### 1. Permission granularity enforcement

**Question:** How deep does “fine-grained authorization” go—row-level, field-level, or just resource-level?
**My Understanding:** Likely resource + operation level, but sensitive fields may need stricter control.
**Solution:** Implement RBAC with resource-operation matrix + optional row-level filters for ownership-based access.

---

### 2. Audit event storage size

**Question:** Audit events are immutable and store before/after values—how to prevent database bloat?
**My Understanding:** Full snapshots for every change will grow fast.
**Solution:** Store diffs instead of full objects + archive older audit logs to cold tables.

---

### 3. Membership “good standing” definition

**Question:** What defines “good standing” for applying member pricing?
**My Understanding:** Likely no outstanding unpaid orders or violations.
**Solution:** Add status flag (ACTIVE, SUSPENDED, DELINQUENT) with validation before applying benefits.

---

### 4. Points accrual edge cases

**Question:** Are points applied before or after discounts?
**My Understanding:** Usually after discounts to prevent abuse.
**Solution:** Calculate points based on final payable subtotal.

---

### 5. Tier upgrade/downgrade timing

**Question:** When do users move between Bronze/Silver/Gold tiers? Real-time or periodic?
**My Understanding:** Real-time upgrade, periodic downgrade.
**Solution:** Upgrade immediately on threshold; run scheduled downgrade checks monthly.

---

### 6. Coupon + campaign conflict resolution

**Question:** What happens if multiple campaigns are eligible?
**My Understanding:** Only one campaign allowed per order.
**Solution:** Apply highest priority campaign and reject others.

---

### 7. Order timeout behavior

**Question:** When order auto-closes after 30 minutes, what happens to inventory?
**My Understanding:** Inventory must be released.
**Solution:** Add timeout job that releases reserved stock and updates order status to CLOSED.

---

### 8. Idempotency key enforcement scope

**Question:** What defines “duplicate order”? Same payload or just same key?
**My Understanding:** Key-based enforcement only.
**Solution:** Store idempotency key per user with 10-minute TTL and reject duplicates.

---

### 9. Partial fulfillment support

**Question:** Can orders be partially fulfilled or must all items be completed together?
**My Understanding:** Not specified; default should be full fulfillment.
**Solution:** Enforce all-or-nothing fulfillment unless extended later.

---

### 10. Work order SLA business hours definition

**Question:** What defines “business hours” for SLA timers?
**My Understanding:** Likely configurable per system.
**Solution:** Add configurable working hours + exclude weekends/holidays.

---

### 11. Technician assignment conflicts

**Question:** Can multiple technicians claim the same work order?
**My Understanding:** Should be prevented.
**Solution:** Use locking mechanism to allow only one active assignment.

---

### 12. Work order cost validation

**Question:** Are parts/costs editable after submission?
**My Understanding:** Should be restricted after approval.
**Solution:** Allow edits only in early states; lock after finalization.

---

### 13. Contribution share validation

**Question:** What happens if contributions don’t sum to exactly 100%?
**My Understanding:** System must enforce strict validation.
**Solution:** Add transactional validation rejecting any non-100% total.

---

### 14. Outcome duplicate detection accuracy

**Question:** Token overlap for abstract similarity—what threshold defines duplicate?
**My Understanding:** Needs tuning to avoid false positives.
**Solution:** Start with 70% overlap threshold + manual review queue.

---

### 15. Interaction rate limit enforcement

**Question:** What happens when users exceed rate limits (comments/reports)?
**My Understanding:** Requests should be blocked.
**Solution:** Implement rate limiter with hard rejection + cooldown tracking.

---
