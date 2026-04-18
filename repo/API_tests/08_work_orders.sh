#!/usr/bin/env bash
# 08_work_orders.sh — Work-order endpoint tests
set -euo pipefail
source "$(dirname "$0")/lib.sh"

print_section "Work Orders"

login admin "Admin@1234!"

# ── POST /v1/work-orders — create ────────────────────────────────────────────

print_section "Create Work Order"

api_call POST /v1/work-orders \
    '{"description":"Screen replacement needed for device #42","attachments":[]}'
assert_status "POST /v1/work-orders → 201" 201 "$HTTP_CODE" "$BODY"
assert_contains "work order has id" '"id"' "$BODY"
assert_contains "work order has workOrderNumber" '"workOrderNumber"' "$BODY"
assert_contains "initial status is SUBMITTED" '"SUBMITTED"' "$BODY"

WO_ID=$(json_val "$BODY" "id")

# Validation: missing description → 400
api_call POST /v1/work-orders '{}'
assert_status "POST /v1/work-orders missing description → 400" 400 "$HTTP_CODE" "$BODY"

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/v1/work-orders" \
    -H "Content-Type: application/json" \
    -d '{"description":"test"}')
assert_status "POST /v1/work-orders without auth → 401" 401 "$HTTP_CODE"

# ── GET /v1/work-orders/{id} ──────────────────────────────────────────────────

print_section "Get Work Order"

if [ -n "$WO_ID" ]; then
    api_call GET "/v1/work-orders/$WO_ID"
    assert_status "GET /v1/work-orders/{id} → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "correct work order returned" '"id"' "$BODY"
    assert_contains "has slaFirstResponseDueAt" '"slaFirstResponseDueAt"' "$BODY"
fi

# Non-existent → 404
api_call GET "/v1/work-orders/00000000-0000-0000-0000-000000000000"
assert_status "GET /v1/work-orders/unknown → 404" 404 "$HTTP_CODE" "$BODY"

# ── POST /v1/work-orders/{id}/claim ──────────────────────────────────────────

print_section "Claim Work Order"

if [ -n "$WO_ID" ]; then
    api_call POST "/v1/work-orders/$WO_ID/claim"
    assert_status "POST /v1/work-orders/{id}/claim → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "status after claim is ASSIGNED" '"ASSIGNED"' "$BODY"
    assert_contains "technician user set" '"technicianUserId"' "$BODY"
fi

# Claim unknown → 404
api_call POST "/v1/work-orders/00000000-0000-0000-0000-000000000000/claim"
assert_status "claim non-existent work order → 404" 404 "$HTTP_CODE" "$BODY"

# ── POST /v1/work-orders/{id}/events — FIRST_RESPONSE_SENT ───────────────────

print_section "Add Work Order Events"

if [ -n "$WO_ID" ]; then
    api_call POST "/v1/work-orders/$WO_ID/events" \
        '{"eventType":"FIRST_RESPONSE_SENT","payload":"Initial diagnosis: screen cracked"}'
    assert_status "POST events FIRST_RESPONSE_SENT → 200" 200 "$HTTP_CODE" "$BODY"

    # STATUS_UPDATED event
    api_call POST "/v1/work-orders/$WO_ID/events" \
        '{"eventType":"STATUS_UPDATED","payload":"Parts ordered"}'
    assert_status "POST events STATUS_UPDATED → 200" 200 "$HTTP_CODE" "$BODY"
fi

# Invalid event type → 400
if [ -n "$WO_ID" ]; then
    api_call POST "/v1/work-orders/$WO_ID/events" \
        '{"eventType":"INVALID_TYPE","payload":"test"}'
    assert_status "POST events invalid eventType → 400" 400 "$HTTP_CODE" "$BODY"
fi

# Events on non-existent work order → 404
api_call POST "/v1/work-orders/00000000-0000-0000-0000-000000000000/events" \
    '{"eventType":"STATUS_UPDATED","payload":"test"}'
assert_status "POST events on unknown work order → 404" 404 "$HTTP_CODE" "$BODY"

# ── PUT /v1/work-orders/{id}/cost ─────────────────────────────────────────────

print_section "Update Work Order Cost"

if [ -n "$WO_ID" ]; then
    api_call PUT "/v1/work-orders/$WO_ID/cost" \
        '{"partsCost":150.00,"laborCost":80.00,"notes":"Screen replacement + labor"}'
    assert_status "PUT /v1/work-orders/{id}/cost → 200" 200 "$HTTP_CODE" "$BODY"
fi

# Cost on non-existent → 404
api_call PUT "/v1/work-orders/00000000-0000-0000-0000-000000000000/cost" \
    '{"partsCost":10.00,"laborCost":5.00}'
assert_status "PUT cost on unknown work order → 404" 404 "$HTTP_CODE" "$BODY"

# ── POST /v1/work-orders/{id}/events — RESOLUTION_CONFIRMED (locks cost) ─────

if [ -n "$WO_ID" ]; then
    api_call POST "/v1/work-orders/$WO_ID/events" \
        '{"eventType":"RESOLUTION_CONFIRMED","payload":"Repair complete"}'
    assert_status "POST events RESOLUTION_CONFIRMED → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "status after resolution" '"RESOLVED"' "$BODY"
fi

# ── POST /v1/work-orders/{id}/rating ─────────────────────────────────────────

print_section "Submit Rating"

if [ -n "$WO_ID" ]; then
    api_call POST "/v1/work-orders/$WO_ID/rating" \
        '{"rating":5,"comment":"Excellent and fast service!"}'
    assert_status "POST /v1/work-orders/{id}/rating → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "work order closed after rating" '"CLOSED"' "$BODY"
fi

# Bean validation fires first (rating=6 exceeds @Max(5)) → 400
if [ -n "$WO_ID" ]; then
    api_call POST "/v1/work-orders/$WO_ID/rating" \
        '{"rating":6,"comment":"Out of range"}'
    assert_status "POST rating out of range (6) → 400" 400 "$HTTP_CODE" "$BODY"
fi

# Work order already rated (ALREADY_RATED) → 409
if [ -n "$WO_ID" ]; then
    api_call POST "/v1/work-orders/$WO_ID/rating" \
        '{"rating":4,"comment":"Already rated"}'
    assert_status "POST rating on already-rated work order → 409" 409 "$HTTP_CODE" "$BODY"
fi

# Rating unknown work order → 404
api_call POST "/v1/work-orders/00000000-0000-0000-0000-000000000000/rating" \
    '{"rating":4,"comment":"test"}'
assert_status "POST rating on unknown work order → 404" 404 "$HTTP_CODE" "$BODY"

# ── GET /v1/work-orders/analytics ────────────────────────────────────────────

print_section "Work Order Analytics"

api_call GET "/v1/work-orders/analytics?from=2020-01-01&to=2030-12-31"
assert_status "GET /v1/work-orders/analytics → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "analytics has total" '"total"' "$BODY"

# Date range with no results still returns valid response
api_call GET "/v1/work-orders/analytics?from=2019-01-01&to=2019-12-31"
assert_status "GET analytics with empty range → 200" 200 "$HTTP_CODE" "$BODY"

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/v1/work-orders/analytics?from=2020-01-01&to=2030-12-31")
assert_status "GET analytics without auth → 401" 401 "$HTTP_CODE"

# ── GET /v1/work-orders/export ────────────────────────────────────────────────

print_section "Work Order Export"

api_call GET "/v1/work-orders/export?format=csv"
assert_status "GET /v1/work-orders/export (csv) → 200" 200 "$HTTP_CODE" "$BODY"

# Verify Content-Type is text/csv
CONTENT_TYPE=$(curl -s -o /dev/null -w "%{content_type}" \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/v1/work-orders/export?format=csv")
assert_contains "export Content-Type is text/csv" "text/csv" "$CONTENT_TYPE"

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/v1/work-orders/export?format=csv")
assert_status "GET export without auth → 401" 401 "$HTTP_CODE"

echo ""
echo "Work order results: pass=$PASS fail=$FAIL"
