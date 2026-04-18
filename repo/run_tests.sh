#!/usr/bin/env bash
# ============================================================================
# run_tests.sh — RICMS unified test runner
#
# Usage:
#   bash run_tests.sh               # start Docker, run unit tests + API tests
#   bash run_tests.sh --unit-only   # start Docker, run unit tests only
#   bash run_tests.sh --api-only    # start Docker, run API tests only
#
# Requirements:
#   - Docker with docker compose
#   - curl and python3 on PATH (for API tests)
# ============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

BASE_URL="http://localhost:8082"

RUN_UNIT=true
RUN_API=true
for arg in "$@"; do
    case "$arg" in
        --unit-only) RUN_API=false ;;
        --api-only)  RUN_UNIT=false ;;
    esac
done

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BOLD='\033[1m'; RESET='\033[0m'
info()    { echo -e "${BOLD}$*${RESET}"; }
success() { echo -e "${GREEN}$*${RESET}"; }
warn()    { echo -e "${YELLOW}$*${RESET}"; }
error()   { echo -e "${RED}$*${RESET}"; }

# ── Docker compose helper ─────────────────────────────────────────────────────
COMPOSE_CMD=""
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD="docker-compose"
else
    error "Docker not found. Install Docker Desktop and try again."
    exit 1
fi

# ── Totals ────────────────────────────────────────────────────────────────────
UNIT_PASS=0; UNIT_FAIL=0
API_PASS=0;  API_FAIL=0
ALL_FAILURES=""

# =============================================================================
# STEP 1 — Ensure the app stack is running
# =============================================================================
start_stack() {
    info ""
    info "════════════════════════════════════════════"
    info "  STARTING DOCKER STACK"
    info "════════════════════════════════════════════"

    $COMPOSE_CMD -f "$SCRIPT_DIR/docker-compose.yml" up -d --build

    info "Waiting for server to become healthy at $BASE_URL (up to 120s)..."
    local elapsed=0
    while ! curl -sf --max-time 3 "$BASE_URL/actuator/health" >/dev/null 2>&1; do
        sleep 5
        elapsed=$((elapsed + 5))
        if [ "$elapsed" -ge 120 ]; then
            error "Server did not become healthy after 120s."
            error "Check logs with:  $COMPOSE_CMD logs app"
            exit 1
        fi
        echo -n "."
    done
    echo ""
    success "Server is up at $BASE_URL"
}

# =============================================================================
# STEP 2 — Unit tests (run inside Docker via the 'test' build stage)
# =============================================================================
run_unit_tests() {
    info ""
    info "════════════════════════════════════════════"
    info "  UNIT TESTS (Docker / JUnit 5 + Mockito)"
    info "════════════════════════════════════════════"

    local output rc
    output=$($COMPOSE_CMD -f "$SCRIPT_DIR/docker-compose.yml" \
        --profile test run --rm unit-tests 2>&1) || rc=$?
    rc=${rc:-0}

    echo "$output"

    if [ $rc -eq 0 ]; then
        UNIT_PASS=$(echo "$output" | grep -oE 'Tests run: [0-9]+' | tail -1 | grep -oE '[0-9]+$' || echo 0)
        UNIT_FAIL=0
        success "Unit tests: ALL PASSED"
    else
        error "Unit tests: FAILURES DETECTED"
        UNIT_FAIL=$(echo "$output" | grep -oE 'Failures: [0-9]+' | grep -oE '[0-9]+$' | awk '{sum+=$1} END {print sum+0}')
        local errs
        errs=$(echo "$output" | grep -oE 'Errors: [0-9]+' | grep -oE '[0-9]+$' | awk '{sum+=$1} END {print sum+0}')
        UNIT_FAIL=$((UNIT_FAIL + errs))
        UNIT_PASS=$(echo "$output" | grep -oE 'Tests run: [0-9]+' | grep -oE '[0-9]+$' | awk '{sum+=$1} END {print sum+0}')
        UNIT_PASS=$((UNIT_PASS - UNIT_FAIL))
        local fail_msgs
        fail_msgs=$(echo "$output" | grep -E "\[ERROR\].*<<<" | sed 's/\[ERROR\] *//' | head -20)
        ALL_FAILURES="$ALL_FAILURES\n  [Unit] $fail_msgs"
    fi
}

# =============================================================================
# STEP 3 — API tests (curl scripts against running server)
# =============================================================================
run_api_tests() {
    export RICMS_BASE_URL="$BASE_URL"

    info ""
    info "════════════════════════════════════════════"
    info "  API TESTS (curl → $BASE_URL)"
    info "════════════════════════════════════════════"

    local scripts=("$SCRIPT_DIR/API_tests"/0*.sh)
    if [ ${#scripts[@]} -eq 0 ] || [ ! -f "${scripts[0]}" ]; then
        warn "No API test scripts found in API_tests/"
        return 0
    fi

    local pre_resp
    pre_resp=$(curl -s -X POST "$BASE_URL/v1/auth/login" \
        -H "Content-Type: application/json" \
        -d '{"username":"admin","password":"Admin@1234!"}')
    export ADMIN_TOKEN
    ADMIN_TOKEN=$(echo "$pre_resp" | python3 -c \
        "import json,sys; print(json.load(sys.stdin).get('accessToken',''))" 2>/dev/null || true)

    for script in "${scripts[@]}"; do
        [ -f "$script" ] || continue
        local name
        name=$(basename "$script" .sh)

        info ""
        info "── Running: $name ──"

        local output rc
        output=$(bash "$script" 2>&1) || true
        rc=$?

        echo "$output"

        local s_pass s_fail
        s_pass=$(echo "$output" | grep -oE 'pass=[0-9]+' | tail -1 | sed 's/pass=//' || echo 0)
        s_fail=$(echo "$output" | grep -oE 'fail=[0-9]+' | tail -1 | sed 's/fail=//' || echo 0)
        API_PASS=$((API_PASS + ${s_pass:-0}))
        API_FAIL=$((API_FAIL + ${s_fail:-0}))

        if [ "${s_fail:-0}" -gt 0 ] || [ $rc -ne 0 ]; then
            local failures
            failures=$(echo "$output" | grep "✗" | sed 's/^/    /')
            ALL_FAILURES="$ALL_FAILURES\n  [API/$name]\n$failures"
        fi
    done
}

# =============================================================================
# Main
# =============================================================================
START_TIME=$SECONDS

start_stack

$RUN_UNIT && run_unit_tests
$RUN_API  && run_api_tests

ELAPSED=$((SECONDS - START_TIME))

info ""
info "════════════════════════════════════════════"
info "  FINAL SUMMARY  (${ELAPSED}s)"
info "════════════════════════════════════════════"

TOTAL_PASS=$((UNIT_PASS + API_PASS))
TOTAL_FAIL=$((UNIT_FAIL + API_FAIL))
TOTAL=$((TOTAL_PASS + TOTAL_FAIL))

printf "  %-20s  %4s total   %4s pass   %4s fail\n" \
    "Unit tests" "$((UNIT_PASS+UNIT_FAIL))" "$UNIT_PASS" "$UNIT_FAIL"
printf "  %-20s  %4s total   %4s pass   %4s fail\n" \
    "API tests" "$((API_PASS+API_FAIL))" "$API_PASS" "$API_FAIL"
echo "  ──────────────────────────────────────────"
printf "  %-20s  %4s total   %4s pass   %4s fail\n" \
    "TOTAL" "$TOTAL" "$TOTAL_PASS" "$TOTAL_FAIL"

if [ "$TOTAL_FAIL" -gt 0 ]; then
    echo ""
    error "  FAILURES:"
    echo -e "$ALL_FAILURES"
    echo ""
    error "  ✗ Test run FAILED  ($TOTAL_FAIL failure(s))"
    exit 1
else
    echo ""
    success "  ✓ All tests passed!"
    exit 0
fi
