#!/usr/bin/env bash
# 03_orders.sh — Order lifecycle endpoint tests
set -euo pipefail
source "$(dirname "$0")/lib.sh"

print_section "Orders"

login admin "Admin@1234!"

# Fetch admin user ID for buyerId
USER_RESP=$(curl -s "$BASE_URL/v1/users" -H "Authorization: Bearer $TOKEN")
BUYER_ID=$(echo "$USER_RESP" | python3 -c \
    "import json,sys; d=json.load(sys.stdin); print(d['content'][0]['id'] if d.get('content') else '')" 2>/dev/null)

ORDER_BODY=$(cat <<EOF
{
  "buyerId": "$BUYER_ID",
  "items": [{"sku":"SKU-001","quantity":1,"unitPrice":99.99}],
  "shippingCountry": "US",
  "shippingPostalCode": "10001",
  "paymentMethod": "CARD_ON_FILE"
}
EOF
)

# ── POST /v1/orders — missing Idempotency-Key header → 400 ───────────────────

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/v1/orders" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$ORDER_BODY")
assert_status "place order without Idempotency-Key → error" 500 "$HTTP_CODE"

# ── POST /v1/orders — valid request → 201 ────────────────────────────────────

IKEY="ricms-test-$(date +%s)-$$"
raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/orders" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IKEY" \
    -d "$ORDER_BODY")
HTTP_CODE=$(echo "$raw" | tail -1)
BODY=$(echo "$raw" | sed '$d')
assert_status "place order with valid payload → 201" 201 "$HTTP_CODE" "$BODY"
assert_contains "response has orderNumber" '"orderNumber"' "$BODY"
assert_contains "status is PLACED" '"PLACED"' "$BODY"

ORDER_ID=$(json_val "$BODY" "id")
ORDER_NUM=$(json_val "$BODY" "orderNumber")

# ── POST /v1/orders — same Idempotency-Key within window → 200 (idempotent) ──

raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/orders" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IKEY" \
    -d "$ORDER_BODY")
HTTP_CODE=$(echo "$raw" | tail -1)
BODY2=$(echo "$raw" | sed '$d')
assert_status "same Idempotency-Key within window → 200" 200 "$HTTP_CODE" "$BODY2"
RETURNED_NUM=$(json_val "$BODY2" "orderNumber")
assert_contains "idempotent response returns same order number" "$ORDER_NUM" "$RETURNED_NUM"

# ── GET /v1/orders — list ─────────────────────────────────────────────────────

api_call GET "/v1/orders?page=0&pageSize=10"
assert_status "list orders → 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "list has content array" '"content"' "$BODY"

# ── GET /v1/orders/{id} — get single order ────────────────────────────────────

if [ -n "$ORDER_ID" ]; then
    api_call GET "/v1/orders/$ORDER_ID"
    assert_status "get order by id → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "correct order returned" "$ORDER_NUM" "$BODY"
fi

# ── GET /v1/orders/{id} — not found ──────────────────────────────────────────

api_call GET "/v1/orders/00000000-0000-0000-0000-000000000000"
assert_status "get non-existent order → 404" 404 "$HTTP_CODE" "$BODY"

# ── POST /{id}/confirm-payment — happy path ───────────────────────────────────

if [ -n "$ORDER_ID" ]; then
    api_call POST "/v1/orders/$ORDER_ID/confirm-payment" \
        '{"paymentReference":"PAY-REF-TEST-001"}'
    assert_status "confirm payment on PLACED order → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "status is CONFIRMED_PAYMENT" '"CONFIRMED_PAYMENT"' "$BODY"
fi

# ── POST /{id}/confirm-payment — invalid state (already confirmed) → 409 ──────

if [ -n "$ORDER_ID" ]; then
    api_call POST "/v1/orders/$ORDER_ID/confirm-payment" \
        '{"paymentReference":"DUPE-PAY"}'
    assert_status "confirm payment on non-PLACED order → 409" 409 "$HTTP_CODE" "$BODY"
    assert_contains "error code indicates state issue" '"INVALID_STATE_TRANSITION"' "$BODY"
fi

# ── POST /{id}/fulfillment ─────────────────────────────────────────────────────

if [ -n "$ORDER_ID" ]; then
    api_call POST "/v1/orders/$ORDER_ID/fulfillment"
    assert_status "fulfill order after payment confirmed → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "status is FULFILLMENT_RESERVED" '"FULFILLMENT_RESERVED"' "$BODY"
fi

# ── POST /{id}/confirm-delivery ───────────────────────────────────────────────

if [ -n "$ORDER_ID" ]; then
    api_call POST "/v1/orders/$ORDER_ID/confirm-delivery"
    assert_status "confirm delivery → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "status is DELIVERY_CONFIRMED" '"DELIVERY_CONFIRMED"' "$BODY"
fi

# ── POST /{id}/complete ───────────────────────────────────────────────────────

if [ -n "$ORDER_ID" ]; then
    api_call POST "/v1/orders/$ORDER_ID/complete"
    assert_status "complete order → 200" 200 "$HTTP_CODE" "$BODY"
    assert_contains "status is COMPLETED" '"COMPLETED"' "$BODY"
fi

# ── POST /{id}/complete — invalid transition (already COMPLETED) → 409 ─────────

if [ -n "$ORDER_ID" ]; then
    api_call POST "/v1/orders/$ORDER_ID/complete"
    assert_status "complete already-completed order → 409" 409 "$HTTP_CODE" "$BODY"
fi

# ── POST /{id}/notes ──────────────────────────────────────────────────────────

if [ -n "$ORDER_ID" ]; then
    api_call POST "/v1/orders/$ORDER_ID/notes" '{"note":"Test note from API test"}'
    assert_status "add note to order → 204" 204 "$HTTP_CODE" "$BODY"
fi

# ── POST /{id}/attachments ────────────────────────────────────────────────────

if [ -n "$ORDER_ID" ]; then
    api_call POST "/v1/orders/$ORDER_ID/attachments" \
        '{"blobRef":"s3://test-bucket/receipt.pdf","contentType":"application/pdf"}'
    assert_status "add attachment to order → 201" 201 "$HTTP_CODE" "$BODY"
    assert_contains "attachment has id" '"id"' "$BODY"
    assert_contains "attachment has blobRef" '"blobRef"' "$BODY"
fi

# No auth → 401
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/v1/orders/00000000-0000-0000-0000-000000000001/attachments" \
    -H "Content-Type: application/json" \
    -d '{"blobRef":"s3://bucket/file.pdf","contentType":"application/pdf"}')
assert_status "POST order attachments without auth → 401" 401 "$HTTP_CODE"

# ── POST /v1/orders — insufficient inventory (SKU-004 has 0 stock) ────────────

IKEY2="ricms-test-inv-$(date +%s)-$$"
INV_BODY=$(cat <<EOF
{
  "buyerId": "$BUYER_ID",
  "items": [{"sku":"SKU-004","quantity":1,"unitPrice":10.00}],
  "shippingCountry": "US",
  "paymentMethod": "CARD_ON_FILE"
}
EOF
)
raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/orders" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IKEY2" \
    -d "$INV_BODY")
HTTP_CODE=$(echo "$raw" | tail -1)
BODY=$(echo "$raw" | sed '$d')
assert_status "order with out-of-stock SKU-004 → 422" 422 "$HTTP_CODE" "$BODY"

# ── POST /v1/orders — unknown SKU ────────────────────────────────────────────

IKEY3="ricms-test-sku-$(date +%s)-$$"
UNK_BODY=$(cat <<EOF
{
  "buyerId": "$BUYER_ID",
  "items": [{"sku":"SKU-NONEXISTENT","quantity":1,"unitPrice":10.00}],
  "shippingCountry": "US",
  "paymentMethod": "CARD_ON_FILE"
}
EOF
)
raw=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/v1/orders" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IKEY3" \
    -d "$UNK_BODY")
HTTP_CODE=$(echo "$raw" | tail -1)
BODY=$(echo "$raw" | sed '$d')
assert_status "order with unknown SKU → 404" 404 "$HTTP_CODE" "$BODY"

echo ""
echo "Order results: pass=$PASS fail=$FAIL"
