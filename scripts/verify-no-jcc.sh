#!/usr/bin/env bash
# Fails if the IBM JDBC driver can reach anyone through a shipped artifact
# (ADR 0048). Thin wrapper: the checking logic lives in verify-no-jcc.py,
# because it has to open nested archives to look for a shaded driver, and
# unzip cannot read a zip inside a zip.
#
# This stays a shell entry point so the call sites in .github/workflows/ci.yml
# and scripts/verify-release.sh do not need to care which interpreter name
# exists on a given machine: ubuntu-latest guarantees python3, Windows
# installs usually provide only python.
#
# Usage: scripts/verify-no-jcc.sh [archive ...]
#   With no argument, checks every distributable archive under target/.
# Exit 0 if clean, 1 on any finding or if nothing could be checked.
set -uo pipefail

cd "$(dirname "$0")/.."

PY=""
for candidate in python3 python py; do
  if command -v "$candidate" >/dev/null 2>&1; then PY="$candidate"; break; fi
done

if [ -z "$PY" ]; then
  echo "verify-no-jcc: no Python interpreter found on PATH (tried python3, python, py)." >&2
  echo "This check inspects nested archives for a shaded IBM driver and cannot" >&2
  echo "be skipped silently - a release must not pass on an unverified artifact." >&2
  exit 1
fi

# --self-test runs the gate against the committed evasion cases instead of
# against a real artifact. Kept on this wrapper so callers need not know
# which interpreter exists.
if [ "${1:-}" = "--self-test" ]; then
  exec "$PY" scripts/verify-no-jcc-cases.py
fi

exec "$PY" scripts/verify-no-jcc.py "$@"
