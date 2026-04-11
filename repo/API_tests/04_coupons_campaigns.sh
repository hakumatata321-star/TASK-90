#!/usr/bin/env bash
# 04_coupons_campaigns.sh — Coupon and Campaign validation tests
set -euo pipefail
source "$(dirname "$0")/lib.sh"

print_section "Coupons & Campaigns"

login admin "Admin@1234!"

ORDER_CTX_500='{"subtotal":500.00,"shippingCost":15.00}'
ORDER_CTX_100='{"subtotal":100.00,"shippingCost":15.00}'

# ── POST /v1/coupons/validate — valid WELCOME10 (10% percentage discount) ─────

api_call POST /v1/coupons/validate \
    "{\"couponCode\":\"WELCOME10\",\"orderContext\":$ORDER_CTX_500}"
assert_status "WELCOME10 coupon → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "WELCOME10 is valid" '"isValid":true' "$BODY"
assert_contains "response has couponType" '"couponType"' "$BODY"
assert_contains "response has discountAmount" '"discountAmount"' "$BODY"

# ── POST /v1/coupons/validate — SAVE50 below threshold ───────────────────────

api_call POST /v1/coupons/validate \
    "{\"couponCode\":\"SAVE50\",\"orderContext\":$ORDER_CTX_100}"
assert_status "SAVE50 below $500 threshold → 200 (invalid)" 200 "$HTTP_CODE" "$BODY"
assert_contains "SAVE50 not applicable below threshold" '"isValid":false' "$BODY"
assert_contains "rejection reason present" '"reason"' "$BODY"

# ── POST /v1/coupons/validate — SAVE50 at threshold → applicable ─────────────

api_call POST /v1/coupons/validate \
    "{\"couponCode\":\"SAVE50\",\"orderContext\":$ORDER_CTX_500}"
assert_status "SAVE50 at threshold → 200 (valid)" 200 "$HTTP_CODE" "$BODY"
assert_contains "SAVE50 valid at threshold" '"isValid":true' "$BODY"

# ── POST /v1/coupons/validate — FREESHIP shipping waiver ─────────────────────

api_call POST /v1/coupons/validate \
    "{\"couponCode\":\"FREESHIP\",\"orderContext\":{\"subtotal\":250.00,\"shippingCost\":15.00}}"
assert_status "FREESHIP above threshold → 200 (valid)" 200 "$HTTP_CODE" "$BODY"
assert_contains "FREESHIP is valid" '"isValid":true' "$BODY"
assert_contains "waivedShipping is true" '"waivedShipping":true' "$BODY"

# ── POST /v1/coupons/validate — unknown coupon code ──────────────────────────

api_call POST /v1/coupons/validate \
    "{\"couponCode\":\"NOSUCHCOUPON\",\"orderContext\":$ORDER_CTX_500}"
assert_status "unknown coupon → 200 (isValid:false)" 200 "$HTTP_CODE" "$BODY"
assert_contains "not found reason" '"isValid":false' "$BODY"

# ── POST /v1/coupons/validate — missing coupon code → rejected ────────────────

api_call POST /v1/coupons/validate \
    "{\"orderContext\":$ORDER_CTX_500}"
assert_status "missing coupon code → 200 (isValid:false)" 200 "$HTTP_CODE" "$BODY"
assert_contains "missing code rejected" '"isValid":false' "$BODY"

# ── POST /v1/coupons/validate — without auth → 401 ───────────────────────────

raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/coupons/validate" \
    -H "Content-Type: application/json" \
    -d "{\"couponCode\":\"WELCOME10\",\"orderContext\":$ORDER_CTX_500}")
HTTP_CODE=$(echo "$raw" | tail -1)
assert_status "coupon validate without auth → 401" 401 "$HTTP_CODE"

# ── POST /v1/campaigns/validate — SPEND_AND_GET above threshold ──────────────

# Get campaign IDs from DB via admin KPIs won't work; fetch campaigns if endpoint exists
# Instead, use a specific campaign UUID from seed data if available, otherwise test auto-select via order

# Get all campaigns by placing a validate with no campaignId (tests auto-select path)
api_call POST /v1/campaigns/validate \
    '{"orderContext":{"subtotal":1200.00,"shippingCost":10.00}}'
assert_status "campaign validate (auto) → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "campaign validate has isValid field" '"isValid"' "$BODY"

# ── POST /v1/campaigns/validate — unknown campaign ID ────────────────────────

api_call POST /v1/campaigns/validate \
    '{"campaignId":"00000000-0000-0000-0000-000000000000","orderContext":{"subtotal":500.00}}'
assert_status "unknown campaign id → 200 (isValid:false)" 200 "$HTTP_CODE" "$BODY"
assert_contains "unknown campaign not valid" '"isValid":false' "$BODY"

# ── POST /v1/campaigns/validate — missing campaign ID in request ──────────────

api_call POST /v1/campaigns/validate \
    '{"orderContext":{"subtotal":0.01}}'
assert_status "campaign validate low subtotal (no eligible campaigns) → 200" 200 "$HTTP_CODE" "$BODY"

echo ""
echo "Coupon/Campaign results: pass=$PASS fail=$FAIL"
