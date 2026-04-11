#!/usr/bin/env bash
# ============================================================================
# run_tests.sh — RICMS unified test runner
#
# Usage:
#   ./run_tests.sh               # run both unit and API tests
#   ./run_tests.sh --unit-only   # skip API tests (server not required)
#   ./run_tests.sh --api-only    # skip Maven unit tests
#   RICMS_BASE_URL=http://host:8080 ./run_tests.sh
#
# Requirements:
#   - JDK 21+ and Maven 3.9+ on PATH  (for unit tests)
#   - curl and python3 on PATH         (for API tests)
#   - RICMS server running at RICMS_BASE_URL (for API tests)
# ============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

export RICMS_BASE_URL="${RICMS_BASE_URL:-http://localhost:8080}"

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

# ── Totals ────────────────────────────────────────────────────────────────────
UNIT_PASS=0; UNIT_FAIL=0
API_PASS=0;  API_FAIL=0
ALL_FAILURES=""

# =============================================================================
# UNIT TESTS  (Maven)
# =============================================================================
run_unit_tests() {
    info ""
    info "════════════════════════════════════════════"
    info "  UNIT TESTS (Maven / JUnit 5 + Mockito)"
    info "════════════════════════════════════════════"

    local mvn_output
    mvn_output=$(mvn test --no-transfer-progress 2>&1)
    local mvn_rc=$?

    # Extract per-class counts from surefire output
    local summary
    summary=$(echo "$mvn_output" | grep "Tests run:" | tail -n +1)
    echo "$summary"

    # Extract aggregate totals from the last "Tests run:" line (the aggregate)
    local agg
    agg=$(echo "$mvn_output" | grep "^\\[INFO\\] Tests run:" | tail -1)
    if [ -n "$agg" ]; then
        echo ""
        echo "$agg"
    fi

    if [ $mvn_rc -eq 0 ]; then
        # Parse totals from the final summary line
        UNIT_PASS=$(echo "$mvn_output" | grep "Tests run:" | tail -1 | \
            grep -oP 'Tests run: \K[0-9]+' || echo 0)
        UNIT_FAIL=0
        success "Unit tests: ALL PASSED"
    else
        # Extract failure details
        local fails
        fails=$(echo "$mvn_output" | grep -E "FAIL|ERROR" | grep -v "^\[" | head -20)
        error "Unit tests: FAILURES DETECTED"
        echo "$fails"

        # Count failures/errors from surefire
        UNIT_FAIL=$(echo "$mvn_output" | grep -oP 'Failures: \K[0-9]+' | \
            awk '{sum+=$1} END {print sum}')
        local errors
        errors=$(echo "$mvn_output" | grep -oP 'Errors: \K[0-9]+' | \
            awk '{sum+=$1} END {print sum}')
        UNIT_FAIL=$((${UNIT_FAIL:-0} + ${errors:-0}))

        UNIT_PASS=$(echo "$mvn_output" | grep -oP 'Tests run: \K[0-9]+' | \
            awk '{sum+=$1} END {print sum}')
        UNIT_PASS=$((${UNIT_PASS:-0} - ${UNIT_FAIL:-0}))

        # Collect failure messages for the final summary
        local fail_msgs
        fail_msgs=$(echo "$mvn_output" | grep -E "\[ERROR\].*<<<" | \
            sed 's/\[ERROR\] *//' | head -20)
        ALL_FAILURES="$ALL_FAILURES\n  [Unit] $fail_msgs"

        # Print relevant log lines for diagnosis
        echo ""
        warn "Key failure log (first 40 relevant lines):"
        echo "$mvn_output" | grep -A3 "<<<" | head -40
    fi
}

# =============================================================================
# API TESTS  (curl scripts)
# =============================================================================
run_api_tests() {
    info ""
    info "════════════════════════════════════════════"
    info "  API TESTS (curl → $RICMS_BASE_URL)"
    info "════════════════════════════════════════════"

    # Check server reachability
    if ! curl -sf --max-time 5 "$RICMS_BASE_URL/actuator/health" >/dev/null 2>&1; then
        warn "Server not reachable at $RICMS_BASE_URL — skipping API tests."
        warn "Start the server with:  docker compose up -d"
        warn "Then re-run:            ./run_tests.sh --api-only"
        return 0
    fi

    local scripts=("$SCRIPT_DIR/API_tests"/0*.sh)
    if [ ${#scripts[@]} -eq 0 ] || [ ! -f "${scripts[0]}" ]; then
        warn "No API test scripts found in API_tests/"
        return 0
    fi

    for script in "${scripts[@]}"; do
        [ -f "$script" ] || continue
        local name
        name=$(basename "$script" .sh)

        info ""
        info "── Running: $name ──"

        # Run script in a sub-shell so its PASS/FAIL vars don't bleed
        local output rc
        output=$(bash "$script" 2>&1) || true
        rc=$?

        echo "$output"

        # Extract pass/fail from the script's last "results:" line
        local s_pass s_fail
        s_pass=$(echo "$output" | grep -oP 'pass=\K[0-9]+' | tail -1 || echo 0)
        s_fail=$(echo "$output" | grep -oP 'fail=\K[0-9]+' | tail -1 || echo 0)
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

$RUN_UNIT  && run_unit_tests
$RUN_API   && run_api_tests

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
