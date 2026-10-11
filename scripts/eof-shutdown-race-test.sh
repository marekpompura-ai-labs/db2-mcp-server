#!/usr/bin/env bash
# Regression test for a real, reproduced bug (ADR 0031): a client that sends
# a tools/call and closes stdin immediately - before the response comes back
# - used to have that response silently dropped. This script drives exactly
# that scenario against the real packaged jar and asserts the response
# still arrives.
#
# Deliberately different from smoke-test.sh, which waits (sleeps) after its
# last message before doing anything else, specifically to *avoid* this
# race and test the ordinary path instead. This script exists to test the
# race path on purpose.
#
# Feeds stdin from a plain file, not a named pipe (mkfifo). A first version
# of this script used mkfifo, matching smoke-test.sh's own technique - but
# a named pipe created by MSYS's mkfifo doesn't produce a Win32 handle a
# native-Windows JVM's stdin reader can use, failing with "IOException: The
# handle is invalid" on Windows via Git Bash, and (worse) leaving the JVM
# hanging indefinitely rather than erroring, since the reader thread's
# exception path never reaches the clean EOF signal
# EofTriggeredShutdownInputStream needs to trigger shutdown. Plain-file
# redirection sidesteps this entirely - `< file` is identical POSIX shell
# behavior on Linux and Windows Git Bash alike, no named-pipe support
# required - while still producing the exact race this test needs: a
# regular file reaches genuine EOF the instant its content is exhausted, so
# the SDK's stdin reader thread reaches EOF almost immediately after
# consuming the last line, while the tool call it just dispatched is still
# running on its own scheduler thread. Confirmed empirically (2026-08-18,
# both on this Windows machine and reasoned to hold identically on Linux,
# since nothing about `< file` redirection or reactor scheduler handoff
# timing is platform-specific) to still reliably create the race - the
# whole reason this script exists - not just a hang-avoiding workaround.
# It's also simpler than the fifo version: no feeder subshell, no polling
# for "server is ready" before sending, since the file's content already
# exists in full before the JVM ever starts reading it.
#
# Usage: scripts/eof-shutdown-race-test.sh (run from the repository root,
# after `mvn -DskipTests package`). Requires jq.
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
echo "EOF-shutdown-race-testing $JAR"

# The driver is not inside the jar (ADR 0048); this launch needs it too.
. "$(dirname "$0")/lib/resolve-jdbc-driver.sh"

STDOUT_FILE=$(mktemp)
STDERR_FILE=$(mktemp)
REQUEST_FILE=$(mktemp)
trap 'rm -f "$STDOUT_FILE" "$STDERR_FILE" "$REQUEST_FILE"' EXIT

# eof-race-test-host.invalid: .invalid is reserved by RFC 2606 to never
# resolve, anywhere - a fast, deterministic DNS failure, matching
# smoke-test.sh's own reasoning. The resulting ~1s connection-timeout delay
# before listSchemas fails is exactly what creates the race window: a
# request still executing when stdin closes immediately after it's sent.
export DB2_HOST="eof-race-test-host.invalid"
export DB2_PORT="50000"
export DB2_DATABASE="EOFRACETEST"
export DB2_USER="eof_race_test_user"
export DB2_PASSWORD="eof_race_test_password"
export DB2MCP_CAPABILITY_PROFILE="SAFE_QUERY"
export DB2MCP_ALLOWEDSCHEMAS="ITSM"

cat > "$REQUEST_FILE" <<'REQUESTS'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"eof-race-test","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"listSchemas","arguments":{}}}
REQUESTS

# No sleep, no keeping anything open afterward - once the JVM has consumed
# every line of $REQUEST_FILE, its very next read reaches genuine EOF,
# well before the ~1s DB2 connection-timeout response could possibly be
# ready. This is the exact scenario ADR 0031 fixes. A 30s ceiling is
# defense-in-depth against a genuine regression back to hanging (the
# failure mode the old mkfifo version had on Windows) - the app should
# always self-terminate well under that once its EOF-triggered shutdown
# sequence (InFlightToolCallTracker drain, then exit) completes, no
# SIGTERM needed.
if command -v timeout >/dev/null 2>&1; then
  timeout 30 java -Dloader.path="$DRIVER_PATH" -jar "$JAR" < "$REQUEST_FILE" > "$STDOUT_FILE" 2> "$STDERR_FILE"
else
  java -Dloader.path="$DRIVER_PATH" -jar "$JAR" < "$REQUEST_FILE" > "$STDOUT_FILE" 2> "$STDERR_FILE"
fi
EXIT_CODE=$?

FAILED=0

if [ "$EXIT_CODE" = 124 ]; then
  echo "FAIL: timed out after 30s without the JVM exiting - looks like a hang, not a slow shutdown (the exact failure mode the old mkfifo-based version of this script had on Windows; if this is happening on Linux/CI, that's a real regression)" >&2
  FAILED=1
elif [ "$EXIT_CODE" != 0 ] && [ "$EXIT_CODE" != 143 ]; then
  echo "FAIL: unexpected exit code $EXIT_CODE after stdin EOF (expected 0 or 143)" >&2
  FAILED=1
fi

TOOLS_CALL_RESPONSE=$(jq -c 'select(.id == 3)' "$STDOUT_FILE" 2>/dev/null | head -1)
if [ -z "$TOOLS_CALL_RESPONSE" ]; then
  echo "FAIL: no response to tools/call (id=3) - the in-flight response was dropped by the EOF race (ADR 0031 regressed)" >&2
  FAILED=1
elif ! echo "$TOOLS_CALL_RESPONSE" | jq -e '.result.content' >/dev/null 2>&1; then
  echo "FAIL: tools/call (id=3) response has no result.content" >&2
  FAILED=1
fi

echo "----- stdout (MCP responses) -----"
cat "$STDOUT_FILE"
echo "----- stderr (last 20 lines) -----"
tail -20 "$STDERR_FILE"
echo "-----------------------------------"

if [ "$FAILED" -ne 0 ]; then
  echo "EOF SHUTDOWN RACE TEST FAILED"
  exit 1
fi

echo "EOF SHUTDOWN RACE TEST PASSED: a tools/call response survived stdin closing immediately after the request was sent."
