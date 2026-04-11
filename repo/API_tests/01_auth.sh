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

echo ""
echo "Auth results: pass=$PASS fail=$FAIL"
