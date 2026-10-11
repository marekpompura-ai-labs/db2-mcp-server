#!/usr/bin/env bash
# Thin wrapper over the third-party notices completeness check, so callers
# do not need to know whether this machine has python3, python or py.
# See scripts/generate-third-party-notices.py.
set -uo pipefail
cd "$(dirname "$0")/.."
PY=""
for c in python3 python py; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
if [ -z "$PY" ]; then
  echo "notices-check: no Python interpreter found (tried python3, python, py)." >&2
  exit 1
fi
"$PY" scripts/verify-notice-material-cases.py || exit "$?"
exec "$PY" scripts/generate-third-party-notices.py --check
