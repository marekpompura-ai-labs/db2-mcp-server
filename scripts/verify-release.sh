#!/usr/bin/env bash
#
# Release verification for the public edition.
#
# Every check here is one that a human (or an AI) has already got wrong on
# this project at least once, by reporting a stage as passing without
# having run it. The point of this script is that "verified" means a
# command exited zero, not that someone believed it would.
#
# Findings this would have caught, all of them real:
#   - mvnw and the shell scripts committed as 100644, so `./mvnw` in CI
#     would have failed with Permission denied on the first public run
#     (the working tree looked fine; core.fileMode=false hid it)
#   - README/CHANGELOG declaring 1.0.0 while pom.xml and the MCP server
#     metadata still said 0.1.0-SNAPSHOT
#   - two Windows-1252 bytes making an ADR invalid UTF-8
#   - SpotBugs failures, twice, because only a subset of tests was run
#     locally and `verify` never reached the static-analysis phase
#   - an ADR count asserted in prose that did not match the files on disk
#
# Usage:  scripts/verify-release.sh [--fast]
#           --fast   skip the full Maven build (everything else still runs)
#
# Exit code is non-zero if any mandatory check fails.

set -uo pipefail
cd "$(dirname "$0")/.."

RELEASE_VERSION="1.0.1"
EXPECTED_SBOM_SPEC="1.6"

FAST=0
[ "${1:-}" = "--fast" ] && FAST=1

pass=0; fail=0; skip=0
FAILURES=""

ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail+1)); FAILURES="${FAILURES}
  - $1"; }
skp()  { printf '  \033[33mSKIP\033[0m  %s\n' "$1"; skip=$((skip+1)); }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

SHA="$(git rev-parse HEAD 2>/dev/null || echo 'not-a-git-repo')"
printf '\033[1mRelease verification\033[0m\n'
printf '  commit   %s\n' "$SHA"
printf '  branch   %s\n' "$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '-')"
printf '  date     %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '  version  %s\n' "$RELEASE_VERSION"

# ----------------------------------------------------------------------
head_ "Repository hygiene"

if [ -z "$(git status --porcelain 2>/dev/null)" ]; then
  ok "working tree is clean"
else
  bad "working tree has uncommitted changes"
fi

# Executable bits live in the Git index, not the working tree. On Windows
# with core.fileMode=false a chmod never reaches the index at all, which is
# exactly how these were lost.
modes_bad=""
for f in mvnw scripts/*.sh; do
  [ -e "$f" ] || continue
  m="$(git ls-files -s "$f" | awk '{print $1}')"
  [ "$m" = "100755" ] || modes_bad="$modes_bad $f($m)"
done
if [ -z "$modes_bad" ]; then ok "mvnw and scripts are 100755 in the index"
else bad "not executable in the Git index:$modes_bad"; fi

# ----------------------------------------------------------------------
head_ "Encoding and links"

python - <<'PY'
import os, sys, io, re
nonutf, broken = [], []
for root, dirs, files in os.walk("."):
    dirs[:] = [d for d in dirs if d not in (".git", "target", "node_modules")]
    for f in files:
        p = os.path.join(root, f)
        if f.endswith((".md",".java",".yml",".yaml",".xml",".json",".txt",".sql",".sh",".properties")):
            try:
                t = io.open(p, encoding="utf-8").read()
            except UnicodeDecodeError as e:
                nonutf.append(f"{p} (offset {e.start})"); continue
            if f.endswith(".md"):
                for m in re.finditer(r"\]\((?!https?://|#|mailto:)([^)#\s]+)", t):
                    if not os.path.exists(os.path.normpath(os.path.join(root, m.group(1)))):
                        broken.append(f"{p} -> {m.group(1)}")
io.open(".verify-encoding", "w").write("\n".join(nonutf))
io.open(".verify-links", "w").write("\n".join(broken))
PY
if [ ! -s .verify-encoding ]; then ok "every text file decodes as UTF-8"
else bad "non-UTF-8 files: $(tr '\n' ' ' < .verify-encoding)"; fi
if [ ! -s .verify-links ]; then ok "every relative Markdown link resolves"
else bad "broken links: $(tr '\n' ' ' < .verify-links)"; fi
rm -f .verify-encoding .verify-links

# ----------------------------------------------------------------------
head_ "Release identity"

pom_v="$(python -c "import re,io;print(re.search(r'<artifactId>db2-mcp-server</artifactId>\s*<version>([^<]+)</version>', io.open('pom.xml',encoding='utf-8').read()).group(1))" 2>/dev/null)"
[ "$pom_v" = "$RELEASE_VERSION" ] && ok "pom.xml version is $RELEASE_VERSION" || bad "pom.xml version is '$pom_v', expected $RELEASE_VERSION"

mcp_v="$(grep -A3 'mcp:' src/main/resources/application.yml | grep -m1 'version:' | awk '{print $2}')"
[ "$mcp_v" = "$RELEASE_VERSION" ] && ok "MCP server metadata version is $RELEASE_VERSION" || bad "MCP metadata version is '$mcp_v', expected $RELEASE_VERSION"

grep -q "^## \[$RELEASE_VERSION\]" CHANGELOG.md && ok "CHANGELOG has a [$RELEASE_VERSION] section" || bad "CHANGELOG has no [$RELEASE_VERSION] section"

# The ADR count is asserted in prose in two places; both have been wrong.
#
# Compares the HIGHEST claim against the files on disk, rather than
# requiring every claim to be identical. The original rule required one
# unique value across README.md and CHANGELOG.md, which was right while
# there was exactly one release and became wrong as soon as there were two:
# CHANGELOG's [1.0.0] section says "47 Architecture Decision Records"
# because 1.0.0 shipped 47, and that sentence is a true statement about a
# past release. Freezing it would be a lie; bumping it would rewrite
# history to satisfy a script.
#
# Taking the maximum keeps the property the check was actually written to
# protect - prose drifting behind reality. Add an ADR without updating the
# living claim and the maximum stays behind the file count, and this fails.
# A lower historical claim is expected and is reported, not failed.
adr_actual="$(ls docs/adr/[0-9][0-9][0-9][0-9]-*.md 2>/dev/null | wc -l | tr -d ' ')"
adr_claims="$(grep -hoE '\*\*[0-9]+ (ADRs|Architecture Decision Records)\*\*' README.md CHANGELOG.md | grep -oE '[0-9]+' | sort -un | tr '\n' ' ')"
adr_max="$(printf '%s\n' $adr_claims | sort -n | tail -1)"
if [ "$adr_max" = "$adr_actual" ]; then
  adr_old="$(printf '%s ' $adr_claims | sed "s/\b$adr_max\b//" | tr -s ' ' | sed 's/^ *//;s/ *$//')"
  if [ -n "$adr_old" ]; then
    ok "current ADR count claimed in prose ($adr_actual) matches the files on disk (historical claims for earlier releases: $adr_old)"
  else
    ok "ADR count claimed in prose ($adr_actual) matches the files on disk"
  fi
else
  bad "ADR count mismatch: files=$adr_actual, highest prose claim=$adr_max (all claims: $adr_claims)"
fi

# No ADR may sit in Proposed in a release: shipping a component whose own
# ADR still gates it was a real pre-publication blocker.
proposed="$(for f in docs/adr/[0-9]*.md; do
  st=$(awk '/^## Status/{getline; while($0 ~ /^$/) getline; print; exit}' "$f")
  case "$st" in Accepted*|\*\*Accepted*) ;; *) basename "$f";; esac
done | tr '\n' ' ')"
[ -z "$proposed" ] && ok "no ADR is left in a non-Accepted state" || bad "ADRs not Accepted: $proposed"

# ----------------------------------------------------------------------
head_ "Sanitization"

# The pattern is assembled from fragments on purpose: written out whole it
# would appear literally in this file and the scan would flag itself. The
# first run of this script did exactly that.
priv_pat="db2-mcp-server-""private\|db2-mcp-server-""draft"
git ls-files -z | xargs -0 grep -lI "$priv_pat" 2>/dev/null > .verify-priv
if [ -s .verify-priv ]; then
  bad "private-repository reference in: $(tr '\n' ' ' < .verify-priv)"
else
  ok "no private-repository references"
fi
rm -f .verify-priv

# Personal paths and the developer's own machine.
if git ls-files -z | xargs -0 grep -lIE "C:\\\\dev\\\\|/home/[a-z]+/db2|Users\\\\marco" 2>/dev/null | head -1 | grep -q .; then
  bad "personal filesystem paths present"
else
  ok "no personal filesystem paths"
fi

# ----------------------------------------------------------------------
head_ "Build, tests and static analysis"

if [ "$FAST" = "1" ]; then
  skp "full Maven build (--fast)"
else
  # `verify`, not `test`: SpotBugs, Spotless, Enforcer and the JaCoCo gate
  # all bind after the test phase. Running only tests is how two SpotBugs
  # failures reached CI from a "green" local run.
  if ./mvnw -B clean verify -DskipITs > .verify-build.log 2>&1; then
    ok "./mvnw clean verify (tests, ArchUnit, contract snapshots, Spotless, SpotBugs, JaCoCo)"
  else
    bad "./mvnw clean verify failed - see .verify-build.log"
    tail -25 .verify-build.log | sed 's/^/        /'
  fi
fi

# ----------------------------------------------------------------------
head_ "Packaged artifact and SBOM"

if [ "$FAST" = "1" ] && [ ! -f "target/db2-mcp-server-$RELEASE_VERSION.jar" ]; then
  skp "packaged artifact (no build in --fast mode)"
else
  [ -f "target/db2-mcp-server-$RELEASE_VERSION.jar" ] \
    && ok "packaged jar is db2-mcp-server-$RELEASE_VERSION.jar" \
    || bad "expected target/db2-mcp-server-$RELEASE_VERSION.jar"
fi

# ADR 0048. The 1.0.0 build produced a jar containing the IBM driver,
# because the dependency had no <scope> and nobody looked. (Never
# distributed - the repo is private and the release carries no binary
# assets - but it would have been.) Delegated to a dedicated script, which
# checks every distributable archive under target/ and opens nested jars to
# catch a shaded driver, so the same check is runnable against any artifact.
if [ "$FAST" = "1" ] && [ ! -f "target/db2-mcp-server-$RELEASE_VERSION.jar" ]; then
  skp "no IBM driver in the shipped jar (no build in --fast mode)"
else
  if out="$(bash scripts/verify-no-jcc.sh 2>&1)"; then
    ok "no IBM driver in any shipped archive, at any nesting level (ADR 0048)"
  else
    bad "verify-no-jcc.sh failed: $(printf '%s' "$out" | head -2 | tr '\n' ' ')"
  fi

  # The gate itself has been wrong twice - a name pattern that missed
  # jcc-12.1.5.0.jar, then a scanner that opened only one nested level.
  # Test it, do not trust it.
  if out="$(bash scripts/verify-no-jcc.sh --self-test 2>&1)"; then
    ok "the no-JCC gate catches every known evasion (15 cases)"
  else
    bad "verify-no-jcc self-test failed: $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
  fi

  # Attribution extracted from an artifact is not an obligation
  # discharged (2026-10-10 review). This fails if any shipped component
  # lacks its required licence material.
  if out="$(bash scripts/notices-check.sh 2>&1)"; then
    ok "every shipped component's licence obligation is discharged"
  else
    bad "third-party compliance: $(printf '%s' "$out" | head -3 | tr '\n' ' ')"
  fi
fi

sbom="target/classes/META-INF/sbom/application.cdx.json"
if [ -f "$sbom" ]; then
  spec="$(python -c "import json,io;print(json.load(io.open('$sbom',encoding='utf-8')).get('specVersion'))")"
  root="$(python -c "import json,io;d=json.load(io.open('$sbom',encoding='utf-8'));print(d.get('metadata',{}).get('component',{}).get('version'))")"
  [ "$spec" = "$EXPECTED_SBOM_SPEC" ] && ok "SBOM specVersion is $EXPECTED_SBOM_SPEC (BSI TR-03183-2 minimum)" \
                                      || bad "SBOM specVersion is '$spec', expected $EXPECTED_SBOM_SPEC"
  [ "$root" = "$RELEASE_VERSION" ] && ok "SBOM root component version is $RELEASE_VERSION" \
                                   || bad "SBOM root component version is '$root'"
else
  if [ "$FAST" = "1" ]; then skp "SBOM (no build in --fast mode)"; else bad "no SBOM generated at $sbom"; fi
fi

# ----------------------------------------------------------------------
head_ "Optional external scanners"

if command -v gitleaks >/dev/null 2>&1; then
  gitleaks detect --no-banner --redact -s . >/dev/null 2>&1 \
    && ok "gitleaks found no secrets" || bad "gitleaks reported findings"
else
  skp "gitleaks not installed locally (CI runs it on every push)"
fi

# ----------------------------------------------------------------------
printf '\n\033[1mResult\033[0m\n'
printf '  passed %s   failed %s   skipped %s\n' "$pass" "$fail" "$skip"
printf '  commit %s\n' "$SHA"
if [ "$fail" -gt 0 ]; then
  printf '\n\033[31mNOT READY\033[0m%s\n\n' "$FAILURES"
  exit 1
fi
printf '\n\033[32mAll mandatory checks passed\033[0m\n\n'
