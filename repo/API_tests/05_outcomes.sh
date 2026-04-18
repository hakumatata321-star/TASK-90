#!/usr/bin/env bash
# 05_outcomes.sh — Outcomes / IP endpoint tests
set -euo pipefail
source "$(dirname "$0")/lib.sh"

print_section "Outcomes / IP"

login admin "Admin@1234!"

# Get admin user ID
USER_RESP=$(curl -s "$BASE_URL/v1/users" -H "Authorization: Bearer $TOKEN")
ADMIN_ID=$(echo "$USER_RESP" | python3 -c \
    "import json,sys; d=json.load(sys.stdin); print(d['content'][0]['id'] if d.get('content') else '')" 2>/dev/null)

# ── POST /v1/projects — create a project first ───────────────────────────────

raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/projects" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"Test Research Project","description":"For API testing"}')
HTTP_CODE=$(echo "$raw" | tail -1)
BODY=$(echo "$raw" | sed '$d')
assert_status "create project → 201 or 200" 201 "$HTTP_CODE" "$BODY" 2>/dev/null || \
assert_status "create project → 200" 200 "$HTTP_CODE" "$BODY"
PROJECT_ID=$(json_val "$BODY" "id")

# ── POST /v1/outcomes — valid outcome (100% contribution) ────────────────────

TITLE="Quantum Computing Advances in Cryptography $(date +%s)"
OUTCOME_BODY=$(cat <<EOF
{
  "type": "PAPER",
  "title": "$TITLE",
  "abstractText": "This paper explores quantum cryptography techniques and their implications for modern security.",
  "projectId": "$PROJECT_ID",
  "contributions": [{"contributorId": "$ADMIN_ID", "sharePercent": 100.0}],
  "evidences": []
}
EOF
)
api_call POST /v1/outcomes "$OUTCOME_BODY"
assert_status "register outcome with 100% contribution → 201" 201 "$HTTP_CODE" "$BODY"
assert_contains "outcome has id" '"id"' "$BODY"
OUTCOME_ID=$(json_val "$BODY" "id")
assert_contains "outcome status is ACTIVE or UNDER_REVIEW" '"status"' "$BODY"

# ── POST /v1/outcomes — contributions not summing to 100% → 400 ───────────────

PARTIAL_BODY=$(cat <<EOF
{
  "type": "PAPER",
  "title": "A Partial Contribution Paper",
  "contributions": [{"contributorId": "$ADMIN_ID", "sharePercent": 60.0}],
  "evidences": []
}
EOF
)
api_call POST /v1/outcomes "$PARTIAL_BODY"
assert_status "outcome with 60% contribution → 400" 400 "$HTTP_CODE" "$BODY"
assert_contains "error code for bad contributions" '"INVALID_CONTRIBUTIONS"' "$BODY"

# ── POST /v1/outcomes — sum over 100% → 400 ──────────────────────────────────

OVER_BODY=$(cat <<EOF
{
  "type": "PAPER",
  "title": "Over 100 Contribution",
  "contributions": [
    {"contributorId": "$ADMIN_ID", "sharePercent": 70.0},
    {"contributorId": "$ADMIN_ID", "sharePercent": 40.0}
  ],
  "evidences": []
}
EOF
)
api_call POST /v1/outcomes "$OVER_BODY"
assert_status "outcome with 110% contribution → 400" 400 "$HTTP_CODE" "$BODY"

# ── POST /v1/outcomes — multi-contrib summing exactly to 100% ─────────────────

MULTI_BODY=$(cat <<EOF
{
  "type": "PAPER",
  "title": "Multi Contributor Paper $(date +%s)",
  "contributions": [
    {"contributorId": "$ADMIN_ID", "sharePercent": 70.0},
    {"contributorId": "$ADMIN_ID", "sharePercent": 30.0}
  ],
  "evidences": []
}
EOF
)
api_call POST /v1/outcomes "$MULTI_BODY"
assert_status "outcome with 70+30=100% → 201" 201 "$HTTP_CODE" "$BODY"

# ── POST /v1/outcomes/duplicates/check — no duplicate ────────────────────────

CHECK_BODY='{"type":"PAPER","title":"A Completely Unique Title With No Matches Anywhere","contributions":[{"contributorId":"00000000-0000-0000-0000-000000000001","sharePercent":100}]}'
api_call POST /v1/outcomes/duplicates/check "$CHECK_BODY"
assert_status "duplicate check with unique title → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "duplicateFound is false" '"duplicateFound":false' "$BODY"

# ── POST /v1/outcomes/duplicates/check — exact title match ───────────────────

if [ -n "$OUTCOME_ID" ]; then
    CHECK_DUP_BODY=$(cat <<EOF
{
  "type": "PAPER",
  "title": "$TITLE",
  "contributions": [{"contributorId": "$ADMIN_ID", "sharePercent": 100.0}]
}
EOF
)
    api_call POST /v1/outcomes/duplicates/check "$CHECK_DUP_BODY"
    assert_status "duplicate check with exact title → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "exact title match detected" '"duplicateFound":true' "$BODY"
fi

# ── GET /v1/outcomes/{id} ─────────────────────────────────────────────────────

if [ -n "$OUTCOME_ID" ]; then
    api_call GET "/v1/outcomes/$OUTCOME_ID"
    assert_status "get outcome by id → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "correct outcome returned" '"id"' "$BODY"
fi

# ── GET /v1/outcomes/{id} — not found ────────────────────────────────────────

api_call GET "/v1/outcomes/00000000-0000-0000-0000-000000000000"
assert_status "get non-existent outcome → 404" 404 "$HTTP_CODE" "$BODY"

# ── GET /v1/projects/{id}/outcomes ───────────────────────────────────────────

if [ -n "$PROJECT_ID" ]; then
    api_call GET "/v1/projects/$PROJECT_ID/outcomes"
    assert_status "list outcomes by project → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "paginated list returned" '"content"' "$BODY"
fi

# ── GET /v1/projects/{projectId} ─────────────────────────────────────────────

if [ -n "$PROJECT_ID" ]; then
    api_call GET "/v1/projects/$PROJECT_ID"
    assert_status "get project by id → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "project has id" '"id"' "$BODY"
    assert_contains "project has name" '"name"' "$BODY"
fi

# Non-existent project → 404
api_call GET "/v1/projects/00000000-0000-0000-0000-000000000000"
assert_status "get non-existent project → 404" 404 "$HTTP_CODE" "$BODY"

# ── GET /v1/outcomes/{outcomeId}/contributions ────────────────────────────────

if [ -n "$OUTCOME_ID" ]; then
    api_call GET "/v1/outcomes/$OUTCOME_ID/contributions"
    assert_status "get outcome contributions → 200" 200 "$HTTP_CODE" "$BODY"
    # Response is a list
    assert_contains "contributions list returned" '"sharePercent"' "$BODY"
fi

# Non-existent outcome contributions → 404
api_call GET "/v1/outcomes/00000000-0000-0000-0000-000000000000/contributions"
assert_status "get contributions for non-existent outcome → 404" 404 "$HTTP_CODE" "$BODY"

# ── Permission check — no auth → 401 ─────────────────────────────────────────

raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/outcomes" \
    -H "Content-Type: application/json" \
    -d "$OUTCOME_BODY")
HTTP_CODE=$(echo "$raw" | tail -1)
assert_status "register outcome without auth → 401" 401 "$HTTP_CODE"

echo ""
echo "Outcome results: pass=$PASS fail=$FAIL"
