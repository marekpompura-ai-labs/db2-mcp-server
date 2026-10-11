#!/usr/bin/env python
"""Self-test for scripts/verify-no-jcc.py.

A release gate that silently stops catching things is worse than no gate,
and this one has now been wrong twice:

  * its first pattern required `jcc.jar` exactly, so it passed an archive
    containing `jcc-12.1.5.0.jar`;
  * its second version opened only one nested level and ignored nested ZIP
    entries, so it passed `release.zip -> app.jar -> lib/innocent.jar ->
    DB2Driver.class` and `release.zip -> payload.zip -> lib/jcc.jar`.

Both were found by running the scanner against synthetic containers, not by
reading it. So the containers are checked in, and the scanner is tested the
same way the application is.

Builds each container in a temporary directory, runs the scanner against it,
and asserts the exit code. Requires a packaged jar under target/ to use as a
realistic host archive.

Usage: python scripts/verify-no-jcc-cases.py
Exit 0 if every case behaves as specified, 1 otherwise.
"""

import io
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCANNER = os.path.join(ROOT, "scripts", "verify-no-jcc.py")
CLASS_MAGIC = b"\xca\xfe\xba\xbe"


def app_jar():
    target = os.path.join(ROOT, "target")
    if not os.path.isdir(target):
        return None
    for name in sorted(os.listdir(target)):
        if (name.startswith("db2-mcp-server-") and name.endswith(".jar")
                and "sources" not in name and "javadoc" not in name):
            return os.path.join(target, name)
    return None


def zip_bytes(entries):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for path, data in entries:
            z.writestr(path, data)
    return buf.getvalue()


def copy_with(src, extra, dst):
    shutil.copy(src, dst)
    with zipfile.ZipFile(dst, "a") as z:
        for path, data in extra:
            z.writestr(path, data)
    return dst


def build_cases(tmp, app):
    """Return [(label, path, expected_exit), ...]."""
    driver_lib = zip_bytes([
        ("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"),
        ("org/example/Helper.class", CLASS_MAGIC),
        ("com/ibm/db2/jcc/DB2Driver.class", CLASS_MAGIC),
    ])
    cases = []
    j = lambda n: os.path.join(tmp, n)                         # noqa: E731

    # --- must FAIL -------------------------------------------------------
    cases.append((
        "driver artifact directly in BOOT-INF/lib",
        copy_with(app, [("BOOT-INF/lib/jcc-12.1.5.0.jar", b"PK\x03\x04")],
                  j("c01.jar")), 1))
    cases.append((
        "db2jcc4.jar, the LI's named Redistributable",
        copy_with(app, [("BOOT-INF/lib/db2jcc4.jar", b"PK\x03\x04")],
                  j("c02.jar")), 1))
    cases.append((
        "licence-activation jar",
        copy_with(app, [("BOOT-INF/lib/db2jcc_license_cisuz.jar", b"PK")],
                  j("c03.jar")), 1))
    cases.append((
        "sqlj4.zip in an unexpected path",
        copy_with(app, [("WEB-INF/odd/place/sqlj4.zip", b"PK\x03\x04")],
                  j("c04.jar")), 1))
    cases.append((
        "driver classes unpacked into the archive",
        copy_with(app, [("BOOT-INF/classes/com/ibm/db2/jcc/DB2Driver.class",
                         CLASS_MAGIC)], j("c05.jar")), 1))
    cases.append((
        "driver SHADED inside a bundled library",
        copy_with(app, [("BOOT-INF/lib/innocent-utils-1.0.0.jar",
                         driver_lib)], j("c06.jar")), 1))

    # The two false negatives the 2026-10-10 review reproduced.
    tainted = copy_with(app, [("BOOT-INF/lib/innocent-utils-1.0.0.jar",
                               driver_lib)], j("c07-inner.jar"))
    with zipfile.ZipFile(j("c07.zip"), "w") as z:
        z.writestr("README.txt", "release bundle")
        z.write(tainted, "db2-mcp-server.jar")
    cases.append((
        "release.zip -> app.jar -> lib/innocent.jar -> driver class",
        j("c07.zip"), 1))

    with zipfile.ZipFile(j("c08.zip"), "w") as z:
        z.writestr("README.txt", "release bundle")
        z.writestr("payload.zip",
                   zip_bytes([("lib/jcc-12.1.5.0.jar", b"PK\x03\x04")]))
    cases.append((
        "release.zip -> payload.zip -> lib/jcc.jar",
        j("c08.zip"), 1))

    # Inner ARTIFACT name, not class name - missed by the second version.
    cases.append((
        "app.jar -> lib/bundler.jar -> lib/jcc.jar (inner name)",
        copy_with(app, [("BOOT-INF/lib/bundler.jar",
                         zip_bytes([("lib/jcc-12.1.5.0.jar",
                                     b"PK\x03\x04")]))], j("c09.jar")), 1))

    with zipfile.ZipFile(j("c10.zip"), "w") as z:
        z.writestr("app.war",
                   zip_bytes([("WEB-INF/lib/jcc-12.1.5.0.jar",
                               b"PK\x03\x04")]))
    cases.append((
        "release.zip -> app.war -> WEB-INF/lib/jcc.jar",
        j("c10.zip"), 1))

    # Fail closed: an archive nothing can open is not a pass.
    cases.append((
        "unreadable nested archive - must fail closed",
        copy_with(app, [("BOOT-INF/lib/corrupt.jar", b"not a zip at all")],
                  j("c11.jar")), 1))

    # Launcher regression: ZIP layout reverted to JarLauncher.
    src = zipfile.ZipFile(app)
    with zipfile.ZipFile(j("c12.jar"), "w", zipfile.ZIP_DEFLATED) as out:
        for info in src.infolist():
            data = src.read(info.filename)
            if info.filename == "META-INF/MANIFEST.MF":
                data = data.replace(b"PropertiesLauncher", b"JarLauncher")
            out.writestr(info, data)
    src.close()
    cases.append((
        "Main-Class reverted to JarLauncher",
        j("c12.jar"), 1))

    # --- must PASS -------------------------------------------------------
    cases.append(("the real packaged jar", app, 0))

    with zipfile.ZipFile(j("c13.zip"), "w") as z:
        z.writestr("README.txt", "release bundle")
        z.write(app, "db2-mcp-server.jar")
    cases.append(("clean release.zip containing the real jar",
                  j("c13.zip"), 0))

    # The descriptive configuration string must NOT be flagged: it appears
    # in real configuration and in this project's own error message.
    cases.append((
        "descriptive config string only - must NOT be flagged",
        copy_with(app, [("BOOT-INF/classes/application-extra.yml",
                         "spring:\n  datasource:\n    driver-class-name: "
                         "com.ibm.db2.jcc.DB2Driver\n")], j("c14.jar")), 0))

    return cases


def main():
    app = app_jar()
    if not app:
        sys.stderr.write(
            "verify-no-jcc-cases: no packaged jar under target/ - run "
            "'./mvnw -DskipTests package' first\n"
        )
        return 1

    tmp = tempfile.mkdtemp(prefix="verify-no-jcc-cases-")
    try:
        cases = build_cases(tmp, app)
        width = max(len(c[0]) for c in cases)
        bad = 0
        for label, path, expected in cases:
            result = subprocess.call(
                [sys.executable, SCANNER, path],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
            ok = result == expected
            if not ok:
                bad += 1
            sys.stdout.write(
                "  %-*s  expect %d  got %d  %s\n"
                % (width, label, expected, result, "ok" if ok else "WRONG")
            )
        sys.stdout.write("\n")
        if bad:
            sys.stderr.write(
                "verify-no-jcc-cases: %d of %d cases behaved incorrectly - "
                "the gate is not trustworthy\n" % (bad, len(cases))
            )
            return 1
        sys.stdout.write(
            "verify-no-jcc-cases: all %d cases correct\n" % len(cases)
        )
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
