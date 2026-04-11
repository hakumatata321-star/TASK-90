#!/usr/bin/env bash
# 06_interactions.sh — Interaction governance endpoint tests
set -euo pipefail
source "$(dirname "$0")/lib.sh"

print_section "Interaction Governance"

login admin "Admin@1234!"

# Use a dummy content ID for comment target
CONTENT_ID="00000000-0000-0000-0000-000000000001"

# ── POST /v1/interactions/comments — happy path ───────────────────────────────

api_call POST /v1/interactions/comments \
    "{\"contentType\":\"OUTCOME\",\"contentId\":\"$CONTENT_ID\",\"text\":\"This is a great research outcome!\"}"
assert_status "post comment → 201" 201 "$HTTP_CODE" "$BODY"
assert_contains "comment has id" '"id"' "$BODY"
assert_contains "comment status is ACTIVE" '"status":"ACTIVE"' "$BODY"
COMMENT_ID=$(json_val "$BODY" "id")

# ── GET /v1/interactions/comments — list ─────────────────────────────────────

api_call GET "/v1/interactions/comments?contentType=OUTCOME&contentId=$CONTENT_ID"
assert_status "get comments → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "comments list has content" '"content"' "$BODY"

# ── GET /v1/interactions/comments — pagination ───────────────────────────────

api_call GET "/v1/interactions/comments?contentType=OUTCOME&contentId=$CONTENT_ID&page=0&pageSize=5"
assert_status "get comments with pagination → 200" 200 "$HTTP_CODE" "$BODY"

# ── POST /v1/interactions/comments — missing fields → 400 ────────────────────

api_call POST /v1/interactions/comments '{}'
assert_status "comment with empty body → 400" 400 "$HTTP_CODE" "$BODY"

api_call POST /v1/interactions/comments \
    "{\"contentType\":\"OUTCOME\",\"contentId\":\"$CONTENT_ID\"}"
assert_status "comment without text → 400" 400 "$HTTP_CODE" "$BODY"

# ── POST /v1/interactions/comments — sensitive word → PENDING status ──────────

# First ensure sensitive_words param contains "spam"
api_call POST /v1/admin/operational-params \
    '{"key":"sensitive_words","valueJson":"[\"spam\",\"abuse\",\"scam\"]"}'
# (May need ADMIN:WRITE — admin has it)

api_call POST /v1/interactions/comments \
    "{\"contentType\":\"OUTCOME\",\"contentId\":\"$CONTENT_ID\",\"text\":\"This post is spam and should be moderated\"}"
assert_status "comment with sensitive word → 201 (saved as PENDING)" 201 "$HTTP_CODE" "$BODY"
# The comment is saved but with PENDING status, not rejected
assert_contains "sensitive word comment status is PENDING" '"status":"PENDING"' "$BODY"

# ── POST /v1/interactions/likes ───────────────────────────────────────────────

api_call POST /v1/interactions/likes \
    "{\"contentType\":\"OUTCOME\",\"contentId\":\"$CONTENT_ID\"}"
assert_status "add like → 201" 201 "$HTTP_CODE" "$BODY"

# Idempotent: second like to same content → still 200
api_call POST /v1/interactions/likes \
    "{\"contentType\":\"OUTCOME\",\"contentId\":\"$CONTENT_ID\"}"
assert_status "duplicate like is idempotent → 201" 201 "$HTTP_CODE" "$BODY"

# ── POST /v1/interactions/favorites ──────────────────────────────────────────

api_call POST /v1/interactions/favorites \
    "{\"contentType\":\"OUTCOME\",\"contentId\":\"$CONTENT_ID\"}"
assert_status "add favorite → 201" 201 "$HTTP_CODE" "$BODY"

# Idempotent: second favorite → still 200
api_call POST /v1/interactions/favorites \
    "{\"contentType\":\"OUTCOME\",\"contentId\":\"$CONTENT_ID\"}"
assert_status "duplicate favorite is idempotent → 201" 201 "$HTTP_CODE" "$BODY"

# ── POST /v1/interactions/reports ─────────────────────────────────────────────

if [ -n "$COMMENT_ID" ]; then
    api_call POST /v1/interactions/reports \
        "{\"contentType\":\"COMMENT\",\"contentId\":\"$COMMENT_ID\",\"reason\":\"Spam content in comment\"}"
    assert_status "report content → 201" 201 "$HTTP_CODE" "$BODY"
fi

# ── POST /v1/interactions/reports — missing fields → 400 ─────────────────────

api_call POST /v1/interactions/reports '{}'
assert_status "report with empty body → 400" 400 "$HTTP_CODE" "$BODY"

api_call POST /v1/interactions/reports \
    "{\"contentType\":\"COMMENT\",\"contentId\":\"$CONTENT_ID\"}"
assert_status "report without reason → 400" 400 "$HTTP_CODE" "$BODY"

# ── No auth → 401 ─────────────────────────────────────────────────────────────

raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/interactions/comments" \
    -H "Content-Type: application/json" \
    -d "{\"contentType\":\"OUTCOME\",\"contentId\":\"$CONTENT_ID\",\"text\":\"Unauth\"}")
HTTP_CODE=$(echo "$raw" | tail -1)
assert_status "comment without auth → 401" 401 "$HTTP_CODE"

echo ""
echo "Interaction results: pass=$PASS fail=$FAIL"
