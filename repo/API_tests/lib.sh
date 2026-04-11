#!/usr/bin/env bash
# Shared helpers for RICMS API test scripts
# Source this file: source "$(dirname "$0")/lib.sh"

BASE_URL="${RICMS_BASE_URL:-http://localhost:8080}"

# Counters (exported so the parent can aggregate them)
PASS=0
FAIL=0
FAILURES=""

# ---------------------------------------------------------------------------
# json_val <json> <key>   — extract a top-level JSON string/number value
# Works without jq by using python3.
# ---------------------------------------------------------------------------
json_val() {
    local json="$1" key="$2"
    echo "$json" | python3 -c \
        "import json,sys; d=json.load(sys.stdin); print(d.get('$key',''))" 2>/dev/null
}

# ---------------------------------------------------------------------------
# assert_status <desc> <expected_http_code> <actual_http_code> [body]
# ---------------------------------------------------------------------------
assert_status() {
    local desc="$1" expected="$2" actual="$3" body="${4:-}"
    if [ "$actual" = "$expected" ]; then
        echo "  ✓ $desc"
        ((PASS++))
    else
        echo "  ✗ $desc  (expected HTTP $expected, got HTTP $actual)"
        if [ -n "$body" ]; then
            echo "    $(echo "$body" | head -c 300)"
        fi
        ((FAIL++))
        FAILURES="$FAILURES\n    • $desc"
    fi
}

# ---------------------------------------------------------------------------
# assert_contains <desc> <substring> <string>
# ---------------------------------------------------------------------------
assert_contains() {
    local desc="$1" needle="$2" haystack="$3"
    if echo "$haystack" | grep -q "$needle"; then
        echo "  ✓ $desc"
        ((PASS++))
    else
        echo "  ✗ $desc  (expected to contain: $needle)"
        echo "    got: $(echo "$haystack" | head -c 200)"
        ((FAIL++))
        FAILURES="$FAILURES\n    • $desc"
    fi
}

# ---------------------------------------------------------------------------
# assert_not_contains <desc> <substring> <string>
# ---------------------------------------------------------------------------
assert_not_contains() {
    local desc="$1" needle="$2" haystack="$3"
    if ! echo "$haystack" | grep -q "$needle"; then
        echo "  ✓ $desc"
        ((PASS++))
    else
        echo "  ✗ $desc  (expected NOT to contain: $needle)"
        ((FAIL++))
        FAILURES="$FAILURES\n    • $desc"
    fi
}

# ---------------------------------------------------------------------------
# login <username> <password>  → sets TOKEN env var
# ---------------------------------------------------------------------------
login() {
    local user="$1" pass="$2"
    local resp
    resp=$(curl -s -X POST "$BASE_URL/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$user\",\"password\":\"$pass\"}")
    TOKEN=$(json_val "$resp" "accessToken")
    export TOKEN
}

# ---------------------------------------------------------------------------
# api_call <method> <path> [body]  → sets HTTP_CODE and BODY
# ---------------------------------------------------------------------------
api_call() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-s -w "\n%{http_code}" -X "$method" "$BASE_URL$path"
                -H "Authorization: Bearer $TOKEN"
                -H "Content-Type: application/json")
    if [ -n "$body" ]; then
        args+=(-d "$body")
    fi
    local raw
    raw=$(curl "${args[@]}")
    HTTP_CODE=$(echo "$raw" | tail -1)
    BODY=$(echo "$raw" | head -n -1)
    export HTTP_CODE BODY
}

# ---------------------------------------------------------------------------
# api_call_noauth <method> <path> [body]
# ---------------------------------------------------------------------------
api_call_noauth() {
    local method="$1" path="$2" body="${3:-}"
    local args=(-s -w "\n%{http_code}" -X "$method" "$BASE_URL$path"
                -H "Content-Type: application/json")
    if [ -n "$body" ]; then
        args+=(-d "$body")
    fi
    local raw
    raw=$(curl "${args[@]}")
    HTTP_CODE=$(echo "$raw" | tail -1)
    BODY=$(echo "$raw" | head -n -1)
    export HTTP_CODE BODY
}

# ---------------------------------------------------------------------------
# wait_for_server  — poll health endpoint until app is up (max 120s)
# ---------------------------------------------------------------------------
wait_for_server() {
    echo "Waiting for server at $BASE_URL ..."
    local i=0
    while ! curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; do
        sleep 3
        ((i+=3))
        if [ $i -ge 120 ]; then
            echo "ERROR: server not reachable at $BASE_URL after 120s"
            exit 1
        fi
    done
    echo "Server is up."
}

# ---------------------------------------------------------------------------
# print_section <name>
# ---------------------------------------------------------------------------
print_section() {
    echo ""
    echo "━━━ $1 ━━━"
}
