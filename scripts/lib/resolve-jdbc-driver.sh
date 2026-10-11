#!/usr/bin/env bash
# Resolve the externally supplied IBM JDBC driver and export DRIVER_PATH.
#
# Source this; do not execute it:
#   . "$(dirname "$0")/lib/resolve-jdbc-driver.sh"
#   java -Dloader.path="$DRIVER_PATH" -jar "$JAR" ...
#
# Shared because it has to be identical everywhere, and once was not. The
# driver is deliberately not inside the jar (ADR 0048), so every script that
# launches the packaged jar must supply it. When that decision landed,
# smoke-test.sh was updated and eof-shutdown-race-test.sh was not - and the
# miss stayed hidden because the Trivy step fails earlier in the same CI job
# and the EOF step never ran. It surfaced only once Trivy went green, as
# "Cannot load driver class: com.ibm.db2.jcc.DB2Driver". One copy now, so
# the next change cannot update one caller and miss the other.
#
# DB2MCP_JDBC_DRIVER_PATH is how an operator points at their own copy. The
# local-Maven-repository fallback exists only so developers and CI, which
# already resolved the test-scope artifact to run the tests against, do not
# have to set it by hand.

if [ -n "${DB2MCP_JDBC_DRIVER_PATH:-}" ]; then
  DRIVER_PATH="$DB2MCP_JDBC_DRIVER_PATH"
else
  JCC_VERSION=$(sed -n 's:.*<db2-jcc.version>\(.*\)</db2-jcc.version>.*:\1:p' pom.xml | head -1)
  DRIVER_PATH="${HOME}/.m2/repository/com/ibm/db2/jcc/${JCC_VERSION}/jcc-${JCC_VERSION}.jar"
fi

if [ ! -f "$DRIVER_PATH" ]; then
  echo "IBM JDBC driver not found at: $DRIVER_PATH" >&2
  echo "The driver is not distributed with this project (ADR 0048). Set" >&2
  echo "DB2MCP_JDBC_DRIVER_PATH to your own copy, obtained under IBM's terms." >&2
  exit 1
fi

echo "Using externally supplied JDBC driver: $DRIVER_PATH"
