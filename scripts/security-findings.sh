#!/usr/bin/env bash
# Prints the identity of every Find Security Bugs finding in a SpotBugs XML
# report, one per line, sorted - so a baseline can be diffed rather than
# counted (#93).
#
# Counting alone cannot detect substitution: if one known finding disappears
# and one new vulnerability appears, the total is unchanged and a
# count-based check reports "at baseline". That was the defect this script
# exists to remove.
#
# Identity is detector|class|method, deliberately NOT including the line
# number: line numbers move whenever anything above them is edited, which
# would make the baseline churn on every unrelated change and train the
# reader to ignore it.
#
# Lambda method names are normalised (lambda$executeQuery$6 ->
# lambda$executeQuery) because javac's synthetic index shifts when any
# earlier lambda is added or removed in the same class - again, churn that
# says nothing about security.
#
# Usage:  scripts/security-findings.sh [path-to-spotbugsXml.xml]
set -euo pipefail

REPORT="${1:-target/spotbugsXml.xml}"

if [ ! -f "$REPORT" ]; then
    echo "No SpotBugs report at $REPORT" >&2
    echo "Generate one with: ./mvnw verify -DskipTests -Psecurity-scan" >&2
    exit 2
fi

python3 - "$REPORT" <<'PYTHON'
import re
import sys
import xml.etree.ElementTree as ET

# Force LF regardless of platform. Python's text mode writes CRLF on
# Windows, so a baseline generated on a developer's Windows machine would
# never match the one CI generates on Linux - the check would fail for a
# reason that has nothing to do with security findings.
sys.stdout.reconfigure(newline="\n")

root = ET.parse(sys.argv[1]).getroot()
identities = []

for bug in root.iter("BugInstance"):
    detector = bug.get("type", "?")
    cls = bug.find("Class")
    classname = cls.get("classname", "?") if cls is not None else "?"
    method = bug.find("Method")
    methodname = method.get("name", "?") if method is not None else "?"
    # javac numbers synthetic lambdas per class; the index is not stable
    # across unrelated edits, so drop it.
    methodname = re.sub(r"^(lambda\$[A-Za-z0-9_]+)\$\d+$", r"\1", methodname)
    identities.append(f"{detector}|{classname}|{methodname}")

for identity in sorted(identities):
    print(identity)
PYTHON
