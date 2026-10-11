#!/usr/bin/env python3
"""Regression cases for material checks; no Maven build or database required."""
import hashlib
import importlib.util
import io
# NOTE: every read_text/write_text below passes encoding="utf-8"
# explicitly. Without it, Python on Windows uses the ANSI code page
# (cp1252 here) and test_epl_source_statement_is_required dies with
# UnicodeDecodeError on byte 0x9d - a curly quote in the EPL-2.0 text.
# The suite passed on Linux and failed on the release machine.
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location(
    "notices", ROOT / "scripts/generate-third-party-notices.py")
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


class MaterialChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sup = self.root / "third-party-licenses"
        self.sup.mkdir()
        self.sbom = self.root / "bom.json"
        self.jar = self.root / "app.jar"
        self.manifest = {"elections": {}, "licence_texts": {}}
        for key, value in {
            "ROOT": str(self.root), "SUPPLEMENT_DIR": str(self.sup),
            "SUPPLEMENT": str(self.sup / "MANIFEST.json"),
            "SBOM": str(self.sbom),
        }.items():
            active = patch.object(notices, key, value)
            active.start()
            self.addCleanup(active.stop)

    def collect(self, licence="MIT", body="Copyright Example", corrupt=False):
        (self.sup / "MANIFEST.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        self.sbom.write_text(json.dumps({"components": [{
            "group": "example", "name": "library", "version": "1",
            "licenses": [{"license": {"id": licence}}],
        }]}), encoding="utf-8")
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, "w") as z:
            z.writestr("META-INF/NOTICE", body)
        with zipfile.ZipFile(self.jar, "w") as z:
            z.writestr("BOOT-INF/lib/library-1.jar",
                       b"invalid archive" if corrupt else inner.getvalue())
        return notices.collect(str(self.jar))[2][0]

    def test_notice_alone_does_not_discharge_mit(self):
        self.assertEqual(self.collect()[4], "BLOCKED")

    def test_notice_alone_does_not_discharge_bsd(self):
        self.assertEqual(self.collect("BSD-2-Clause")[4], "BLOCKED")

    def test_unknown_licence_fails_despite_notice(self):
        self.assertEqual(self.collect("Unknown-Licence")[4], "BLOCKED")

    def test_actual_antlr_bsd_text_passes(self):
        body = (ROOT / "third-party-licenses/BSD-3-Clause-antlr4-4.13.1.txt").read_text(encoding="utf-8")
        self.assertTrue(notices.sufficient_material("BSD-3-Clause", [body]))

    def test_missing_bsd_endorsement_clause_fails(self):
        body = (ROOT / "third-party-licenses/BSD-3-Clause-antlr4-4.13.1.txt").read_text(encoding="utf-8")
        body = body[:body.index("3. Neither")] + body[body.index("THIS SOFTWARE"):]
        self.assertFalse(notices.sufficient_material("BSD-3-Clause", [body]))

    def test_corrupt_library_is_not_omitted_from_checks(self):
        self.assertEqual(self.collect(corrupt=True)[4], "BLOCKED")

    def test_bsd_cannot_be_exempted(self):
        self.manifest["no_material_required"] = {"example:library:1": "wrong"}
        self.assertEqual(self.collect("BSD-2-Clause")[4], "BLOCKED")

    def test_missing_election_fails(self):
        self.assertEqual(self.collect("MIT OR BSD-2-Clause")[4], "BLOCKED")

    def test_version_pinned_mit_supplement_satisfies_material_check(self):
        name = "MIT-jtokkit-1.1.0.txt"
        data = (ROOT / "third-party-licenses" / name).read_bytes()
        (self.sup / name).write_bytes(data)
        self.manifest["licence_texts"][name] = {
            "sha256": hashlib.sha256(data).hexdigest(),
            "covers": ["example:library:1"],
        }
        self.assertEqual(self.collect()[4], "satisfied")

    def test_supplement_tampering_fails(self):
        (self.sup / "MIT.txt").write_text("changed", encoding="utf-8")
        self.manifest["licence_texts"]["MIT.txt"] = {"sha256": "0" * 64}
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.collect()

    def test_epl_source_statement_is_required(self):
        data = (ROOT / "third-party-licenses/EPL-2.0.txt").read_text(encoding="utf-8")
        self.assertFalse(notices.sufficient_material("EPL-2.0", [data]))
        (self.root / "SOURCE-AVAILABILITY.md").write_text("source statement", encoding="utf-8")
        self.assertTrue(notices.sufficient_material("EPL-2.0", [data]))

    def legal_jar(self, stale=False, missing=False):
        names = ["LICENSE", "NOTICE", "LICENSE-HISTORY.md",
                 "LICENSE-ELECTIONS.md", "ENTERPRISE-EVALUATION.md",
                 "SOURCE-AVAILABILITY.md", "THIRD-PARTY-NOTICES.md",
                 "LICENSES/Apache-2.0.txt"]
        with zipfile.ZipFile(self.jar, "w") as z:
            for name in names:
                path = self.root / name
                path.parent.mkdir(exist_ok=True)
                path.write_text("current " + name, encoding="utf-8")
                if not (missing and name == "NOTICE"):
                    z.writestr("META-INF/legal/" + name,
                               "old" if stale and name == "NOTICE"
                               else path.read_bytes())

    def test_matching_embedded_documents_pass(self):
        self.legal_jar()
        notices.verify_embedded_legal(str(self.jar))

    def test_stale_embedded_document_fails(self):
        self.legal_jar(stale=True)
        with self.assertRaisesRegex(ValueError, "stale packaged legal file"):
            notices.verify_embedded_legal(str(self.jar))

    def test_missing_embedded_document_fails(self):
        self.legal_jar(missing=True)
        with self.assertRaisesRegex(ValueError, "missing packaged legal file"):
            notices.verify_embedded_legal(str(self.jar))


if __name__ == "__main__":
    unittest.main(verbosity=2)
