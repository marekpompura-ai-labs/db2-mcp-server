#!/usr/bin/env bash
# Packaged-jar smoke test (brief section 11.2: "smoke test covering server
# startup and at least one end-to-end MCP flow"). Automates the manual
# verification this project has relied on throughout its history - tool
# registration, stdout cleanliness (ADR 0001), the effective-config report
# (ADR 0015), the connectivity check (ADR 0019), credential safety, and
# graceful shutdown (ADR 0016) - so a packaging regression fails CI instead
# of waiting to be caught by hand.
#
# Drives a genuine MCP JSON-RPC session over the packaged jar's real stdio
# (initialize -> notifications/initialized -> tools/list -> tools/call),
# not just a process-health check (CR-007, 2026-08-14 code review, ADR
# 0024) - the same raw-pipe technique used to verify ADR 0018, now
# automated instead of run by hand each time.
#
# Usage: scripts/smoke-test.sh (run from the repository root, after
# `mvn -DskipTests package`). Requires jq.
set -uo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required (JSON-RPC response validation) but was not found on PATH" >&2
  exit 1
fi

JAR=$(find target -maxdepth 1 -name "db2-mcp-server-*.jar" ! -name "*sources*" ! -name "*javadoc*" | head -1)
if [ -z "$JAR" ]; then
  echo "No packaged jar found under target/ - run 'mvn -DskipTests package' first" >&2
  exit 1
fi
echo "Smoke-testing $JAR"

STDOUT_FILE=$(mktemp)
STDERR_FILE=$(mktemp)
FIFO_DIR=$(mktemp -d)
FIFO="$FIFO_DIR/stdin"
mkfifo "$FIFO"

# smoke-test-host.invalid: .invalid is reserved by RFC 2606 to never
# resolve, anywhere - a fast, deterministic DNS failure rather than a real
# network timeout, so the connectivity check (ADR 0019) and any tools/call
# that touches the datasource both fail quickly and predictably in CI.
export DB2_HOST="smoke-test-host.invalid"
export DB2_PORT="50000"
export DB2_DATABASE="SMOKETEST"
export DB2_USER="smoke_test_user"
export DB2_PASSWORD="smoke_test_password"
export DB2MCP_CAPABILITY_PROFILE="SAFE_QUERY"
export DB2MCP_ALLOWEDSCHEMAS="ITSM"

# Keep the FIFO open past the last real message so stdin doesn't EOF before
# the app has had time to respond and the SIGTERM test below runs - closing
# it early would race the app onto the stdin-EOF shutdown path instead
# (the exact isolation bug this script's own history already found once).
(
  # Spring Boot's own "Started ..." line is the last thing printed before
  # ApplicationRunners/context refresh complete - safer to wait for than
  # "Registered tools", which logs earlier during autoconfiguration and
  # isn't proof the stdio transport is actually reading yet.
  DEADLINE=$((SECONDS + 60))
  while [ "$SECONDS" -lt "$DEADLINE" ]; do
    if grep -q "Started Db2McpServerApplication" "$STDERR_FILE" 2>/dev/null; then
      break
    fi
    sleep 0.2
  done
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0"}}}'
  sleep 0.3
  echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  sleep 0.3
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  sleep 0.3
  echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"listSchemas","arguments":{}}}'
  sleep 3
) > "$FIFO" &
FEEDER_PID=$!

# The IBM driver is deliberately NOT inside the jar (ADR 0048), so it has to
# come in from outside on every launch - including this one. That makes this
# smoke test the standing verification that external driver loading actually
# works: if PropertiesLauncher stops honouring -Dloader.path, or the
# repackage layout regresses from ZIP back to JarLauncher, the connectivity
# check below stops reporting a Db2 driver error and starts reporting "no
# suitable driver", and this script fails.
#
# Driver resolution is shared - see scripts/lib/resolve-jdbc-driver.sh.
. "$(dirname "$0")/lib/resolve-jdbc-driver.sh"

java -Dloader.path="$DRIVER_PATH" -jar "$JAR" < "$FIFO" > "$STDOUT_FILE" 2> "$STDERR_FILE" &
APP_PID=$!

# Poll for startup completion rather than a fixed sleep - CI runner speed
# varies, and Db2ConnectivityChecker's line is the last thing the startup
# sequence logs.
DEADLINE=$((SECONDS + 60))
while [ "$SECONDS" -lt "$DEADLINE" ]; do
  if grep -q "Db2 connectivity check:" "$STDERR_FILE" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "FAIL: process exited before completing startup" >&2
    break
  fi
  sleep 1
done

# Now wait for the tools/call response (id 3) to actually land on stdout -
# the feeder's fixed sleeps above are pacing between sends, not proof the
# app has replied yet.
DEADLINE=$((SECONDS + 30))
while [ "$SECONDS" -lt "$DEADLINE" ]; do
  if grep -q '"id":3' "$STDOUT_FILE" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

FAILED=0

# --- ADR 0001: stdout is JSON-RPC only, nothing else --------------------
# Every non-empty line must be valid, well-formed JSON-RPC - not merely
# "stdout is empty" (which the previous version of this script checked,
# without ever sending any protocol traffic to actually exercise the
# claim). A stray log line, banner, or partial write would fail this.
if [ ! -s "$STDOUT_FILE" ]; then
  echo "FAIL: stdout is empty - no MCP responses were received at all" >&2
  FAILED=1
else
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    if ! echo "$line" | jq -e '.jsonrpc == "2.0"' >/dev/null 2>&1; then
      echo "FAIL: stdout contains a non-JSON-RPC line (ADR 0001 violation): $line" >&2
      FAILED=1
    fi
  done < "$STDOUT_FILE"
fi

# --- CR-007: a genuine end-to-end MCP flow, not just process health -----
INITIALIZE_RESPONSE=$(jq -c 'select(.id == 1)' "$STDOUT_FILE" 2>/dev/null | head -1)
if [ -z "$INITIALIZE_RESPONSE" ] || ! echo "$INITIALIZE_RESPONSE" | jq -e '.result.serverInfo' >/dev/null 2>&1; then
  echo "FAIL: no valid initialize response (id=1) with serverInfo" >&2
  FAILED=1
fi

TOOLS_LIST_RESPONSE=$(jq -c 'select(.id == 2)' "$STDOUT_FILE" 2>/dev/null | head -1)
TOOLS_LIST_COUNT=$(echo "$TOOLS_LIST_RESPONSE" | jq '.result.tools | length' 2>/dev/null || echo 0)
if [ "$TOOLS_LIST_COUNT" != "10" ]; then
  echo "FAIL: tools/list (id=2) did not return exactly 10 tools (got: $TOOLS_LIST_COUNT)" >&2
  FAILED=1
fi
if ! echo "$TOOLS_LIST_RESPONSE" | jq -e '.result.tools[] | select(.name == "listSchemas")' >/dev/null 2>&1; then
  echo "FAIL: tools/list (id=2) response did not include listSchemas" >&2
  FAILED=1
fi
if ! echo "$TOOLS_LIST_RESPONSE" | jq -e '.result.tools[] | select(.name == "getCapabilities")' >/dev/null 2>&1; then
  echo "FAIL: tools/list (id=2) response did not include getCapabilities (ADR 0028)" >&2
  FAILED=1
fi

TOOLS_CALL_RESPONSE=$(jq -c 'select(.id == 3)' "$STDOUT_FILE" 2>/dev/null | head -1)
if [ -z "$TOOLS_CALL_RESPONSE" ]; then
  echo "FAIL: no response to tools/call (id=3) - the round trip never completed" >&2
  FAILED=1
elif ! echo "$TOOLS_CALL_RESPONSE" | jq -e '.result.content' >/dev/null 2>&1; then
  echo "FAIL: tools/call (id=3) response has no result.content (success or controlled error both produce this shape)" >&2
  FAILED=1
fi

for pattern in "Registered tools: 10" "Effective configuration:" "Db2 connectivity check:"; do
  if ! grep -q "$pattern" "$STDERR_FILE"; then
    echo "FAIL: expected log line not found: $pattern" >&2
    FAILED=1
  fi
done

# Spans are checked here, before shutdown: SimpleSpanProcessor (ADR 0030)
# exports synchronously as each span ends, so the listSchemas call's spans
# are already in $STDERR_FILE well before SIGTERM. Metrics are checked
# later, after shutdown - LoggingMeterRegistry only flushes periodically or
# on close(), and its push interval hasn't elapsed within this test's short
# window, so checking for it here would fail regardless of whether export
# actually works.
if ! grep -q "i.o.e.logging.LoggingSpanExporter.*mcp.tool listSchemas" "$STDERR_FILE"; then
  echo "FAIL: no LoggingSpanExporter output for the listSchemas call - spans are not being exported (CR-010, ADR 0030)" >&2
  FAILED=1
fi

if grep -qE "smoke_test_user|smoke_test_password" "$STDERR_FILE"; then
  echo "FAIL: credentials leaked into log output" >&2
  FAILED=1
fi

# The tools/call response body itself must not leak credentials either -
# a distinct check from the stderr log check above, since a leak into the
# actual MCP response would be worse (it's what the client sees).
if echo "$TOOLS_CALL_RESPONSE" | grep -qE "smoke_test_user|smoke_test_password"; then
  echo "FAIL: credentials leaked into the tools/call MCP response" >&2
  FAILED=1
fi

if grep -q "Application run failed" "$STDERR_FILE"; then
  echo "FAIL: application startup failed" >&2
  FAILED=1
fi

# Graceful shutdown (ADR 0016): SIGTERM must produce a clean exit. The feeder
# is killed only *after* the app has exited, not before/alongside - killing
# it first would close the FIFO and let the app take the stdin-EOF shutdown
# path instead (a real mix-up caught by checking the log line actually
# produced: EOF shutdown logs "MCP client closed stdin", SIGTERM doesn't).
kill -TERM "$APP_PID" 2>/dev/null || true
wait "$APP_PID" 2>/dev/null
EXIT_CODE=$?
kill "$FEEDER_PID" 2>/dev/null || true

if [ "$EXIT_CODE" != 0 ] && [ "$EXIT_CODE" != 143 ]; then
  echo "FAIL: unexpected exit code $EXIT_CODE after SIGTERM (expected 0 or 143)" >&2
  FAILED=1
fi
if grep -q "MCP client closed stdin" "$STDERR_FILE"; then
  echo "FAIL: process took the stdin-EOF shutdown path instead of SIGTERM - test isolation broken" >&2
  FAILED=1
fi

# --- CR-010/ADR 0030: metrics are genuinely exported, not just instrumented
# Checked against the full $STDERR_FILE, not the truncated tail printed
# below - LoggingMeterRegistry's shutdown dump alone is 40+ lines of JVM
# metrics, easily crowding an earlier line out of a human-facing tail (a
# real false alarm this project hit once, for the span check above: a
# "missing" line turned out to still be in the file, just pushed past the
# display window by this same dump).
if ! grep -q "i.m.c.i.logging.LoggingMeterRegistry" "$STDERR_FILE"; then
  echo "FAIL: no LoggingMeterRegistry output found - metrics are not being exported (CR-010, ADR 0030)" >&2
  FAILED=1
fi

echo "----- stdout (MCP responses) -----"
cat "$STDOUT_FILE"
echo "----- stderr (last 40 lines) -----"
tail -40 "$STDERR_FILE"
echo "-----------------------------------"

rm -rf "$STDOUT_FILE" "$STDERR_FILE" "$FIFO_DIR"

if [ "$FAILED" -ne 0 ]; then
  echo "SMOKE TEST FAILED"
  exit 1
fi

echo "SMOKE TEST PASSED: real MCP session (initialize/tools.list/tools.call) completed, 10 tools registered (SAFE_QUERY), effective-config report present, connectivity check present, no credential leakage, stdout is valid JSON-RPC only, clean shutdown on SIGTERM."
