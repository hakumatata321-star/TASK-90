#!/usr/bin/env bash
# 01_auth.sh — Authentication endpoint tests
set -euo pipefail
source "$(dirname "$0")/lib.sh"

print_section "Authentication"

# ── Happy path: valid credentials ────────────────────────────────────────────

api_call_noauth POST /v1/auth/login '{"username":"admin","password":"Admin@1234!"}'
assert_status "login with valid credentials → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "response contains accessToken" '"accessToken"' "$BODY"

TOKEN=$(json_val "$BODY" "accessToken")
export TOKEN

# ── Wrong password ────────────────────────────────────────────────────────────

api_call_noauth POST /v1/auth/login '{"username":"admin","password":"wrong-password"}'
assert_status "login with wrong password → 401" 401 "$HTTP_CODE" "$BODY"
assert_not_contains "no token in 401 body" '"accessToken"' "$BODY"

# ── Non-existent user ─────────────────────────────────────────────────────────

api_call_noauth POST /v1/auth/login '{"username":"nobody","password":"irrelevant"}'
assert_status "login with unknown user → 401" 401 "$HTTP_CODE" "$BODY"

# ── Missing body fields ───────────────────────────────────────────────────────

api_call_noauth POST /v1/auth/login '{}'
assert_status "login with empty body → 400" 400 "$HTTP_CODE" "$BODY"

# ── Accessing protected endpoint without token ────────────────────────────────

TOKEN="" api_call_noauth GET /v1/members/me
assert_status "protected endpoint without token → 401" 401 "$HTTP_CODE" "$BODY"

# ── Accessing protected endpoint with invalid token ───────────────────────────

TOKEN="not.a.valid.jwt"
api_call GET /v1/members/me
assert_status "protected endpoint with garbage token → 401" 401 "$HTTP_CODE" "$BODY"

# Re-login for subsequent tests
login admin "Admin@1234!"

# ── POST /v1/auth/password/rotate — self-service password change ─────────────

print_section "Password Rotate"

# Create a temporary user to test rotate safely
ROTATE_USER="rotate_test_$(date +%s)_$$"
ROTATE_PASS1="Rotate@Pass1!"
ROTATE_PASS2="Rotate@Pass2!"

api_call POST /v1/users "{\"username\":\"$ROTATE_USER\",\"password\":\"$ROTATE_PASS1\"}"
ROTATE_USER_ID=$(json_val "$BODY" "id")

# Assign MEMBER role so the user gets AUTH_USER_SELF:WRITE permission
if [ -n "$ROTATE_USER_ID" ]; then
    ROLES_RESP=$(curl -s "$BASE_URL/v1/roles" -H "Authorization: Bearer $TOKEN")
    MEMBER_ROLE_ID=$(echo "$ROLES_RESP" | python3 -c \
        "import json,sys; d=json.load(sys.stdin); c=d.get('content',[]); print(next((r['id'] for r in c if r.get('name')=='MEMBER'), ''))" 2>/dev/null)
    if [ -n "$MEMBER_ROLE_ID" ]; then
        api_call POST "/v1/users/$ROTATE_USER_ID/roles" "{\"roleIds\":[\"$MEMBER_ROLE_ID\"]}"
    fi
fi

# login as this temp user
raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$ROTATE_USER\",\"password\":\"$ROTATE_PASS1\"}")
ROTATE_HTTP=$(echo "$raw" | tail -1)
ROTATE_BODY=$(echo "$raw" | sed '$d')
ROTATE_TOKEN=$(echo "$ROTATE_BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null)

if [ -n "$ROTATE_TOKEN" ]; then
    # Happy path: rotate password
    OLD_TOKEN="$TOKEN"
    TOKEN="$ROTATE_TOKEN"
    api_call POST /v1/auth/password/rotate \
        "{\"currentPassword\":\"$ROTATE_PASS1\",\"newPassword\":\"$ROTATE_PASS2\"}"
    assert_status "POST /v1/auth/password/rotate → 204" 204 "$HTTP_CODE" "$BODY"

    # Login with new password → 200
    api_call_noauth POST /v1/auth/login \
        "{\"username\":\"$ROTATE_USER\",\"password\":\"$ROTATE_PASS2\"}"
    assert_status "login with new password after rotate → 200" 200 "$HTTP_CODE" "$BODY"

    # Login with old password should now fail → 401
    api_call_noauth POST /v1/auth/login \
        "{\"username\":\"$ROTATE_USER\",\"password\":\"$ROTATE_PASS1\"}"
    assert_status "login with old password after rotate → 401" 401 "$HTTP_CODE" "$BODY"

    TOKEN="$OLD_TOKEN"
fi

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/v1/auth/password/rotate" \
    -H "Content-Type: application/json" \
    -d '{"currentPassword":"any","newPassword":"newpass123"}')
assert_status "POST /v1/auth/password/rotate without auth → 401" 401 "$HTTP_CODE"

echo ""
echo "Auth results: pass=$PASS fail=$FAIL"
