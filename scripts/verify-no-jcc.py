#!/usr/bin/env python
"""Fail if the IBM JDBC driver can reach anyone through a shipped artifact.

Invoked through scripts/verify-no-jcc.sh, which resolves an interpreter.
Run directly as: python scripts/verify-no-jcc.py [archive ...]

Why this check exists
---------------------
The mistake it catches is silent and was already made once. The 1.0.0 build
produced an application archive containing BOOT-INF/lib/jcc-12.1.5.0.jar.
Nobody decided to redistribute IBM's driver: the dependency had no <scope>,
so it defaulted to compile and spring-boot-maven-plugin bundled it. Nothing
failed. The jar was just 6 MB bigger. (There is no known external
distribution of such an archive - the repository is private and the v1.0.0
release carries no binary assets - which is evidence of none, not proof. The
defect either way was in what the build WOULD have shipped the moment an
archive was published.)

Checking the POM is not sufficient, and neither is dependency scope: Boot's
repackage goal bundles provided-scope dependencies by design. The only
reliable check is to look inside what actually ships.

What it inspects
----------------
RECURSIVELY, at every nesting level, in every archive in the distribution
set:

  * prohibited artifact NAMES - jcc*, db2jcc*, sqlj*, and the
    licence-activation jars IBM's License Information names;
  * driver CLASSES under com/ibm/db2/**;
  * nested .jar, .war, .ear and .zip entries, opened and searched the same
    way, to MAX_DEPTH.

Three false negatives in the previous version, all found by a 2026-10-10
external review running it against synthetic containers rather than reading
it, and all now regression cases in scripts/verify-no-jcc-cases.py:

  * release.zip -> app.jar -> BOOT-INF/lib/innocent.jar -> driver class
    (only one nested level was opened)
  * release.zip -> payload.zip -> lib/jcc-12.1.5.0.jar
    (nested ZIP entries were ignored entirely)
  * app.jar -> BOOT-INF/lib/bundler.jar -> lib/jcc-12.1.5.0.jar
    (inner entries were checked for class names but not artifact names)

Directory discovery was also top-level only while the docstring claimed
"every archive under target/". It now walks, skipping build output that is
not part of any distribution.

It FAILS CLOSED: an archive that cannot be read, at any depth, is a failure,
not a pass. A release must not go out on an artifact nothing could open.

Deliberately NOT matched: the string "com.ibm.db2.jcc". That string
legitimately appears in configuration, documentation and error messages -
"Cannot load driver class: com.ibm.db2.jcc.DB2Driver" is this project's own
intended failure message. Flagging the name rather than the bytes would make
the check fire on correct code.

Exit 0 if clean, 1 on any finding or if nothing could be checked.
"""

import io
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "target")

# Archive kinds that can carry a distribution and that zipfile can open.
ARCHIVE_SUFFIXES = (".jar", ".war", ".ear", ".zip")

# `.original` is the pre-repackage jar spring-boot-maven-plugin leaves
# behind. It is a build intermediate and is never distributed.
SKIP_SUFFIXES = (".jar.original",)

# Build output under target/ that is not part of any distribution. Walking
# into these would scan compiled classes and test scaffolding for no gain.
SKIP_DIRS = {
    "classes", "test-classes", "generated-sources", "generated-test-sources",
    "maven-status", "maven-archiver", "surefire-reports", "failsafe-reports",
    "site", "jacoco", "spotbugs",
}

DRIVER_BY_NAME = re.compile(
    r"(^|/)(jcc|db2jcc|sqlj)[^/]*\.(jar|zip)$|db2jcc_license", re.I
)
DRIVER_CLASS = re.compile(r"(^|/)com/ibm/db2/.*\.class$", re.I)

# Depth 6 covers release.zip -> bundle.zip -> app.jar -> lib/x.jar -> y.jar
# with room to spare. A bound is needed: a self-referential or deeply nested
# archive would otherwise recurse until the process died, which is a failure
# mode indistinguishable from a hang.
MAX_DEPTH = 6

# Total decompressed bytes this run will read out of nested archives. Guards
# against a pathological archive turning a release check into a denial of
# service. 2 GiB is far above any legitimate artifact here (the real jar is
# 41 MB).
MAX_TOTAL_BYTES = 2 * 1024 * 1024 * 1024


class Budget(object):
    """Shared read budget across one invocation."""

    def __init__(self, limit):
        self.remaining = limit

    def take(self, n):
        self.remaining -= n
        return self.remaining > 0


def distributable_archives():
    """Every archive under target/ that could be part of a distribution."""
    if not os.path.isdir(TARGET):
        return []
    found = []
    for dirpath, dirnames, filenames in os.walk(TARGET):
        if dirpath == TARGET:
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in sorted(filenames):
            if name.endswith(SKIP_SUFFIXES):
                continue
            if name.endswith(ARCHIVE_SUFFIXES):
                found.append(os.path.join(dirpath, name))
    return sorted(found)


def scan_archive(data_or_path, label, depth, budget, findings, boot_jars):
    """Recursively inspect one archive.

    `boot_jars` collects (label, main_class) for every archive that looks
    like a Spring Boot application jar, so the launcher assertion can run on
    each of them rather than only on the top-level file.
    """
    if depth > MAX_DEPTH:
        findings.append(
            "%s: nesting deeper than %d levels - not inspected, failing "
            "closed" % (label, MAX_DEPTH)
        )
        return

    try:
        if isinstance(data_or_path, bytes):
            archive = zipfile.ZipFile(io.BytesIO(data_or_path))
        else:
            archive = zipfile.ZipFile(data_or_path)
    except Exception as exc:                                   # noqa: BLE001
        findings.append("%s: could not be opened as an archive (%s) - "
                        "failing closed" % (label, exc))
        return

    try:
        names = [n for n in archive.namelist() if not n.endswith("/")]

        if any(n.startswith("BOOT-INF/") for n in names):
            main_class = None
            if "META-INF/MANIFEST.MF" in archive.namelist():
                try:
                    manifest = archive.read(
                        "META-INF/MANIFEST.MF"
                    ).decode("utf-8", "replace").replace("\r", "")
                    for line in manifest.splitlines():
                        if line.startswith("Main-Class:"):
                            main_class = line.split(":", 1)[1].strip()
                except Exception:                              # noqa: BLE001
                    pass
            boot_jars.append((label, main_class))

        for name in names:
            if DRIVER_BY_NAME.search(name):
                findings.append("prohibited driver artifact: %s!/%s"
                                % (label, name))
            elif DRIVER_CLASS.search(name):
                findings.append("driver class: %s!/%s" % (label, name))

        for name in names:
            if not name.lower().endswith(ARCHIVE_SUFFIXES):
                continue
            try:
                nested = archive.read(name)
            except Exception as exc:                           # noqa: BLE001
                findings.append("%s!/%s: could not be read (%s) - failing "
                                "closed" % (label, name, exc))
                continue
            if not budget.take(len(nested)):
                findings.append(
                    "%s: aborted after reading more than %d bytes of nested "
                    "archives - failing closed"
                    % (label, MAX_TOTAL_BYTES)
                )
                return
            scan_archive(nested, "%s!/%s" % (label, name), depth + 1,
                         budget, findings, boot_jars)
    finally:
        archive.close()


def main(argv):
    explicit = [os.path.abspath(a) for a in argv[1:]]
    archives = explicit or distributable_archives()

    if not archives:
        sys.stderr.write(
            "verify-no-jcc: no distributable archive found under target/ - "
            "run './mvnw -DskipTests package' first\n"
        )
        return 1

    missing = [a for a in archives if not os.path.isfile(a)]
    if missing:
        for a in missing:
            sys.stderr.write("verify-no-jcc: no such file: %s\n" % a)
        return 1

    failed = False
    for path in archives:
        label = os.path.basename(path)
        findings, boot_jars = [], []
        scan_archive(path, label, 0, Budget(MAX_TOTAL_BYTES),
                     findings, boot_jars)

        if findings:
            failed = True
            sys.stderr.write(
                "verify-no-jcc: FAIL - IBM driver content found in %s:\n"
                % label
            )
            for f in findings:
                sys.stderr.write("    %s\n" % f)
            sys.stderr.write(
                "\nThe IBM driver must not be distributed (ADR 0048). Check\n"
                "that com.ibm.db2:jcc is <scope>test</scope> AND that the\n"
                "spring-boot-maven-plugin <excludes> entry for it is still\n"
                "present. Note the path above: a finding nested several\n"
                "levels deep means something re-bundled it, not that the\n"
                "project's own dependency scope changed.\n"
            )
            continue

        # Positive assertion, not merely the absence of a bad entry: with the
        # driver excluded, a Boot archive must launch through
        # PropertiesLauncher, or no operator can supply the driver via
        # -Dloader.path and every deployment breaks. Applied to every Boot
        # jar found, including one nested inside a release bundle.
        bad_launcher = [
            (lbl, mc) for lbl, mc in boot_jars
            if not (mc or "").endswith("PropertiesLauncher")
        ]
        if bad_launcher:
            failed = True
            for lbl, mc in bad_launcher:
                sys.stderr.write(
                    "verify-no-jcc: FAIL - %s has Main-Class '%s'.\n"
                    "With the driver excluded, the jar must launch through\n"
                    "PropertiesLauncher so operators can add it with\n"
                    "-Dloader.path (ADR 0048). Check <layout>ZIP</layout> on\n"
                    "the repackage goal.\n" % (lbl, mc)
                )
            continue

        detail = "no IBM driver at any nesting level"
        if boot_jars:
            detail += "; launcher is PropertiesLauncher in %d Boot archive(s)" \
                      % len(boot_jars)
        sys.stdout.write("verify-no-jcc: OK - %s: %s\n" % (label, detail))

    if failed:
        return 1
    sys.stdout.write(
        "verify-no-jcc: %d archive(s) in the distribution set checked, all "
        "clean\n" % len(archives)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
