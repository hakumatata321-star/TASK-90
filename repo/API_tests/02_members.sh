#!/usr/bin/env bash
# 02_members.sh — Membership endpoint tests
set -euo pipefail
source "$(dirname "$0")/lib.sh"

print_section "Membership"

login admin "Admin@1234!"

# ── GET /v1/members/me ────────────────────────────────────────────────────────

api_call GET /v1/members/me
assert_status "GET /v1/members/me → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "response has tier field" '"tier"' "$BODY"
assert_contains "response has status field" '"status"' "$BODY"
assert_contains "response has pointsBalance field" '"pointsBalance"' "$BODY"

# Extract admin member ID for status update
ADMIN_MEMBER_ID=$(json_val "$BODY" "memberId")

# ── GET /v1/members/me/points/ledger ─────────────────────────────────────────

api_call GET /v1/members/me/points/ledger
assert_status "GET /v1/members/me/points/ledger → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "ledger response has content array" '"content"' "$BODY"

# ── GET /v1/members/me/points/ledger with pagination ─────────────────────────

api_call GET "/v1/members/me/points/ledger?page=0&pageSize=5"
assert_status "ledger with pagination params → 200" 200 "$HTTP_CODE" "$BODY"

# ── PUT /v1/members/{id}/status — happy path ─────────────────────────────────

if [ -n "$ADMIN_MEMBER_ID" ]; then
    api_call PUT "/v1/members/$ADMIN_MEMBER_ID/status" '{"status":"SUSPENDED"}'
    assert_status "admin can set member status to SUSPENDED → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "status is SUSPENDED" '"SUSPENDED"' "$BODY"

    # Restore to ACTIVE
    api_call PUT "/v1/members/$ADMIN_MEMBER_ID/status" '{"status":"ACTIVE"}'
    assert_status "restore member status to ACTIVE → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "status is ACTIVE" '"ACTIVE"' "$BODY"
else
    echo "  ! Skipping status update tests (could not extract memberId)"
fi

# ── PUT /v1/members/{id}/status — invalid status value ───────────────────────

if [ -n "$ADMIN_MEMBER_ID" ]; then
    api_call PUT "/v1/members/$ADMIN_MEMBER_ID/status" '{"status":"INVALID_STATUS"}'
    assert_status "invalid status value → 400" 400 "$HTTP_CODE" "$BODY"
fi

# ── PUT /v1/members/{id}/status — missing status field ────────────────────────

if [ -n "$ADMIN_MEMBER_ID" ]; then
    api_call PUT "/v1/members/$ADMIN_MEMBER_ID/status" '{}'
    assert_status "missing status field → 400" 400 "$HTTP_CODE" "$BODY"
fi

# ── Permission check — non-admin cannot update member status ─────────────────
# Create a regular member user, then try to update another member's status

CREATE_RESP=$(curl -s -X POST "$BASE_URL/v1/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"username":"testmember001","password":"Member@Pass1!"}')

NEW_USER_ID=$(json_val "$CREATE_RESP" "id")
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/v1/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"username":"testmember001","password":"Member@Pass1!"}')

# Login as the new member (no admin role → no USER:WRITE permission)
login testmember001 "Member@Pass1!"
MEMBER_TOKEN="$TOKEN"

if [ -n "$ADMIN_MEMBER_ID" ]; then
    TOKEN="$MEMBER_TOKEN"
    api_call PUT "/v1/members/$ADMIN_MEMBER_ID/status" '{"status":"SUSPENDED"}'
    assert_status "member without USER:WRITE cannot update status → 403" 403 "$HTTP_CODE" "$BODY"
fi

echo ""
echo "Member results: pass=$PASS fail=$FAIL"
