#!/usr/bin/env bash
# 07_admin.sh — Admin endpoint tests
set -euo pipefail
source "$(dirname "$0")/lib.sh"

print_section "Admin Endpoints"

# ── Authenticate as admin ─────────────────────────────────────────────────────
login admin "Admin@1234!"

# ── GET /v1/admin/kpis ────────────────────────────────────────────────────────
print_section "KPI Dashboard"

api_call GET /v1/admin/kpis
assert_status "GET /v1/admin/kpis → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "kpis has totalOrders"          '"totalOrders"'          "$BODY"
assert_contains "kpis has totalRevenue"         '"totalRevenue"'         "$BODY"
assert_contains "kpis has totalMembers"         '"totalMembers"'         "$BODY"
assert_contains "kpis has activeWorkOrders"     '"activeWorkOrders"'     "$BODY"
assert_contains "kpis has pendingModerationItems" '"pendingModerationItems"' "$BODY"

# ── POST /v1/admin/operational-params ─────────────────────────────────────────
print_section "Operational Params"

api_call POST /v1/admin/operational-params \
    '{"key":"test_param","valueJson":"\"test\""}'
assert_status "POST operational-params → 204" 204 "$HTTP_CODE" "$BODY"

# Confirm the value is persisted and readable
api_call GET /v1/admin/operational-params/test_param
assert_status "GET operational-params/test_param → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "stored param value matches" '"test"' "$BODY"

# Overwrite the same key (upsert behaviour)
api_call POST /v1/admin/operational-params \
    '{"key":"test_param","valueJson":"\"updated\""}'
assert_status "POST operational-params upsert → 204" 204 "$HTTP_CODE" "$BODY"

api_call GET /v1/admin/operational-params/test_param
assert_contains "upserted param value matches" '"updated"' "$BODY"

# Missing key → 400 (validation)
api_call POST /v1/admin/operational-params \
    '{"valueJson":"\"no-key\""}'
assert_status "POST operational-params missing key → 400" 400 "$HTTP_CODE" "$BODY"

# Non-existent key → 404
api_call GET /v1/admin/operational-params/nonexistent_key_xyz
assert_status "GET operational-params unknown key → 404" 404 "$HTTP_CODE" "$BODY"

# ── GET /v1/admin/audit-events ────────────────────────────────────────────────
print_section "Audit Events"

api_call GET "/v1/admin/audit-events?page=0&pageSize=5"
assert_status "GET audit-events → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "audit-events has content array"    '"content"'    "$BODY"
assert_contains "audit-events has page metadata"   '"page"'       "$BODY"
assert_contains "audit-events has totalElements"   '"totalElements"' "$BODY"

# Filter by resource type (the operational-param write above emits OPERATIONAL_PARAM)
api_call GET "/v1/admin/audit-events?resourceType=OPERATIONAL_PARAM&page=0&pageSize=5"
assert_status "GET audit-events filtered by resourceType → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "filtered audit-events has content" '"content"' "$BODY"

# ── GET /v1/admin/exports/orders?format=csv ───────────────────────────────────
print_section "Order Export"

api_call GET "/v1/admin/exports/orders?format=csv"
assert_status "GET exports/orders (csv) → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "CSV export has header row" "Order Number" "$BODY"

# Verify Content-Type header is text/csv
CONTENT_TYPE=$(curl -s -o /dev/null -w "%{content_type}" \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL/v1/admin/exports/orders?format=csv")
assert_contains "CSV export Content-Type is text/csv" "text/csv" "$CONTENT_TYPE"

# ── Permission guard: unauthenticated request is rejected ─────────────────────
print_section "Admin Permission Guard"

# No token → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/v1/admin/kpis")
assert_status "GET /v1/admin/kpis without token → 401" 401 "$HTTP_CODE"

# Moderation-queue resolve with valid admin session (resolve non-existent id → 404)
FAKE_ID="00000000-0000-0000-0000-000000000001"
api_call POST "/v1/admin/moderation-queue/$FAKE_ID/resolve" \
    '{"decision":"APPROVE","reason":"test"}'
assert_status "POST moderation-queue/resolve unknown id → 404" 404 "$HTTP_CODE" "$BODY"

# Coupon active toggle — unknown id → 404
api_call PUT "/v1/admin/coupons/$FAKE_ID/active?active=false"
assert_status "PUT /v1/admin/coupons/unknown/active → 404" 404 "$HTTP_CODE" "$BODY"

# Campaign active toggle — unknown id → 404
api_call PUT "/v1/admin/campaigns/$FAKE_ID/active?active=false"
assert_status "PUT /v1/admin/campaigns/unknown/active → 404" 404 "$HTTP_CODE" "$BODY"

# ── Authenticated non-admin user gets 403 on admin endpoints ────────────────

print_section "Non-Admin 403 Guard"

# Create a non-admin user and log in
MEMBER_USER="member_test_$(date +%s)"
api_call POST /v1/users "{\"username\":\"$MEMBER_USER\",\"password\":\"Member@1234!\"}"
# Login as the new member
raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$MEMBER_USER\",\"password\":\"Member@1234!\"}")
MEMBER_TOKEN=$(echo "$raw" | head -n -1 | python3 -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null)

if [ -n "$MEMBER_TOKEN" ]; then
    # KPIs should return 403 for non-admin
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer $MEMBER_TOKEN" \
        "$BASE_URL/v1/admin/kpis")
    assert_status "GET /v1/admin/kpis as member → 403" 403 "$HTTP_CODE"

    # Audit events should return 403 for non-admin
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer $MEMBER_TOKEN" \
        "$BASE_URL/v1/admin/audit-events?page=0&pageSize=5")
    assert_status "GET /v1/admin/audit-events as member → 403" 403 "$HTTP_CODE"

    # Export should return 403 for non-admin
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer $MEMBER_TOKEN" \
        "$BASE_URL/v1/admin/exports/orders?format=csv")
    assert_status "GET /v1/admin/exports/orders as member → 403" 403 "$HTTP_CODE"
fi

echo ""
echo "Admin results: pass=$PASS fail=$FAIL"
