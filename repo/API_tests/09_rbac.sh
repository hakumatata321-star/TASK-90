#!/usr/bin/env bash
# 09_rbac.sh — Users, roles, and permissions endpoint tests
set -euo pipefail
source "$(dirname "$0")/lib.sh"

print_section "RBAC — Users, Roles, Permissions"

login admin "Admin@1234!"

# ── GET /v1/users ─────────────────────────────────────────────────────────────

print_section "List Users"

api_call GET /v1/users
assert_status "GET /v1/users → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "response has content array" '"content"' "$BODY"
assert_contains "response has page metadata" '"page"' "$BODY"

# Pagination params
api_call GET "/v1/users?page=0&pageSize=5"
assert_status "GET /v1/users with pagination → 200" 200 "$HTTP_CODE" "$BODY"

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/v1/users")
assert_status "GET /v1/users without auth → 401" 401 "$HTTP_CODE"

# ── GET /v1/users/{userId} ────────────────────────────────────────────────────

print_section "Get User"

# Get admin user ID from the list
USER_LIST=$(curl -s "$BASE_URL/v1/users" -H "Authorization: Bearer $TOKEN")
ADMIN_USER_ID=$(echo "$USER_LIST" | python3 -c \
    "import json,sys; d=json.load(sys.stdin); c=d.get('content',[]); print(next((u['id'] for u in c if u.get('username')=='admin'), ''))" 2>/dev/null)

if [ -n "$ADMIN_USER_ID" ]; then
    api_call GET "/v1/users/$ADMIN_USER_ID"
    assert_status "GET /v1/users/{id} → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "user has username" '"username"' "$BODY"
    assert_contains "user has id" '"id"' "$BODY"
fi

# Non-existent user → 404
api_call GET "/v1/users/00000000-0000-0000-0000-000000000000"
assert_status "GET /v1/users/unknown → 404" 404 "$HTTP_CODE" "$BODY"

# ── POST /v1/users ────────────────────────────────────────────────────────────

print_section "Create User"

NEW_USER="rbac_test_$(date +%s)_$$"
api_call POST /v1/users "{\"username\":\"$NEW_USER\",\"password\":\"NewUser@Pass1!\"}"
assert_status "POST /v1/users → 201" 201 "$HTTP_CODE" "$BODY"
assert_contains "new user has id" '"id"' "$BODY"
assert_contains "new user has username" '"username"' "$BODY"
NEW_USER_ID=$(json_val "$BODY" "id")

# Duplicate username → 409
api_call POST /v1/users "{\"username\":\"$NEW_USER\",\"password\":\"Another@Pass1!\"}"
assert_status "POST /v1/users duplicate username → 409" 409 "$HTTP_CODE" "$BODY"

# Missing password → 400
api_call POST /v1/users '{"username":"validname"}'
assert_status "POST /v1/users missing password → 400" 400 "$HTTP_CODE" "$BODY"

# Username too short → 400
api_call POST /v1/users '{"username":"ab","password":"Valid@Pass1!"}'
assert_status "POST /v1/users username too short → 400" 400 "$HTTP_CODE" "$BODY"

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/v1/users" \
    -H "Content-Type: application/json" \
    -d '{"username":"anon_user","password":"Anon@Pass1!"}')
assert_status "POST /v1/users without auth → 401" 401 "$HTTP_CODE"

# ── GET /v1/roles ─────────────────────────────────────────────────────────────

print_section "List Roles"

api_call GET /v1/roles
assert_status "GET /v1/roles → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "roles has content array" '"content"' "$BODY"

# Extract admin role ID for later tests
ROLE_LIST_BODY="$BODY"
ADMIN_ROLE_ID=$(echo "$ROLE_LIST_BODY" | python3 -c \
    "import json,sys; d=json.load(sys.stdin); c=d.get('content',[]); print(next((r['id'] for r in c if r.get('name')=='ADMIN'), ''))" 2>/dev/null)
MEMBER_ROLE_ID=$(echo "$ROLE_LIST_BODY" | python3 -c \
    "import json,sys; d=json.load(sys.stdin); c=d.get('content',[]); print(next((r['id'] for r in c if r.get('name')=='MEMBER'), ''))" 2>/dev/null)

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/v1/roles")
assert_status "GET /v1/roles without auth → 401" 401 "$HTTP_CODE"

# ── GET /v1/roles/{roleId} ────────────────────────────────────────────────────

print_section "Get Role"

if [ -n "$ADMIN_ROLE_ID" ]; then
    api_call GET "/v1/roles/$ADMIN_ROLE_ID"
    assert_status "GET /v1/roles/{id} → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "role has name" '"name"' "$BODY"
    assert_contains "role has permissions" '"permissions"' "$BODY"
    assert_contains "ADMIN role returned" '"ADMIN"' "$BODY"
fi

# Non-existent role → 404
api_call GET "/v1/roles/00000000-0000-0000-0000-000000000000"
assert_status "GET /v1/roles/unknown → 404" 404 "$HTTP_CODE" "$BODY"

# ── POST /v1/roles ────────────────────────────────────────────────────────────

print_section "Create Role"

ROLE_NAME="AUDITOR_$(date +%s)_$$"
api_call POST /v1/roles \
    "{\"name\":\"$ROLE_NAME\",\"description\":\"Read-only audit access\"}"
assert_status "POST /v1/roles → 201" 201 "$HTTP_CODE" "$BODY"
assert_contains "new role has id" '"id"' "$BODY"
assert_contains "new role has name AUDITOR" "\"$ROLE_NAME\"" "$BODY"
NEW_ROLE_ID=$(json_val "$BODY" "id")

# Duplicate role name → 409
api_call POST /v1/roles \
    "{\"name\":\"$ROLE_NAME\",\"description\":\"Duplicate\"}"
assert_status "POST /v1/roles duplicate name → 409" 409 "$HTTP_CODE" "$BODY"

# Missing name → 400
api_call POST /v1/roles '{"description":"no name"}'
assert_status "POST /v1/roles missing name → 400" 400 "$HTTP_CODE" "$BODY"

# ── GET /v1/permissions ───────────────────────────────────────────────────────

print_section "List Permissions"

api_call GET /v1/permissions
assert_status "GET /v1/permissions → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "permissions list returned" '"resourceType"' "$BODY"
assert_contains "permissions has operation field" '"operation"' "$BODY"

# Extract a permission ID for use in set-permissions test
PERM_ID=$(echo "$BODY" | python3 -c \
    "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')" 2>/dev/null)

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/v1/permissions")
assert_status "GET /v1/permissions without auth → 401" 401 "$HTTP_CODE"

# ── POST /v1/roles/{roleId}/permissions ──────────────────────────────────────

print_section "Set Role Permissions"

if [ -n "$NEW_ROLE_ID" ] && [ -n "$PERM_ID" ]; then
    api_call POST "/v1/roles/$NEW_ROLE_ID/permissions" \
        "{\"permissionIds\":[\"$PERM_ID\"]}"
    assert_status "POST /v1/roles/{id}/permissions → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "role permissions updated" '"permissions"' "$BODY"
fi

# Empty permissionIds → 400 (NotEmpty validation)
if [ -n "$NEW_ROLE_ID" ]; then
    api_call POST "/v1/roles/$NEW_ROLE_ID/permissions" \
        '{"permissionIds":[]}'
    assert_status "POST /v1/roles/{id}/permissions empty list → 400" 400 "$HTTP_CODE" "$BODY"
fi

# Non-existent role → 404
api_call POST "/v1/roles/00000000-0000-0000-0000-000000000000/permissions" \
    "{\"permissionIds\":[\"${PERM_ID:-00000000-0000-0000-0000-000000000001}\"]}"
assert_status "POST permissions on unknown role → 404" 404 "$HTTP_CODE" "$BODY"

# ── POST /v1/users/{userId}/roles ─────────────────────────────────────────────

print_section "Assign Roles to User"

if [ -n "$NEW_USER_ID" ] && [ -n "$MEMBER_ROLE_ID" ]; then
    api_call POST "/v1/users/$NEW_USER_ID/roles" \
        "{\"roleIds\":[\"$MEMBER_ROLE_ID\"]}"
    assert_status "POST /v1/users/{id}/roles → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "user roles updated" '"roles"' "$BODY"

    # Verify the user now has the MEMBER role
    api_call GET "/v1/users/$NEW_USER_ID"
    assert_contains "user has MEMBER role" '"MEMBER"' "$BODY"
fi

# Assign roles to non-existent user → 404
api_call POST "/v1/users/00000000-0000-0000-0000-000000000000/roles" \
    "{\"roleIds\":[\"${MEMBER_ROLE_ID:-00000000-0000-0000-0000-000000000001}\"]}"
assert_status "POST roles on unknown user → 404" 404 "$HTTP_CODE" "$BODY"

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/v1/users/00000000-0000-0000-0000-000000000001/roles" \
    -H "Content-Type: application/json" \
    -d '{"roleIds":[]}')
assert_status "POST /v1/users/{id}/roles without auth → 401" 401 "$HTTP_CODE"

echo ""
echo "RBAC results: pass=$PASS fail=$FAIL"
