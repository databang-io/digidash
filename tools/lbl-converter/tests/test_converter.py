"""Tests for the LBL -> ECU Model converter.

All .lbl fixtures are SYNTHETIC (invented labels/values), zipped on the
fly with zipfile. No real Ross-Tech content is used.
"""

import io
import json
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src"))

from lbl_converter import cli, model, parser  # noqa: E402


# Synthetic label content (invented, not Ross-Tech data).
BASIC_LBL = "\n".join([
    "; synthetic test label file",
    "; another comment line",
    "A01,Some address line",
    "C05,Coding hint line",
    "L1,Login hint line",
    "001.0,Engine data",
    "001.1,Engine speed,rpm,800-900",
    "001.2,Coolant temperature,°C",
    "001.3,Battery voltage,V,12.0-14.5",
    "001.4,Idle switch state",
    "002.0,Mixture data",
    "002.1,Injection time,ms",
    "002.2,Lambda value",
    "",
])

BASE_LBL = "\n".join([
    "; synthetic base label file",
    "001.0,Base engine data",
    "001.1,Engine speed,rpm",
    "001.2,Coolant temperature,°C",
    "",
])


def make_zip(entries):
    """Create a temporary ZIP file from {name: str|bytes} entries."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, content in entries.items():
            if isinstance(content, str):
                content = content.encode("utf-8")
            zf.writestr(name, content)
    fd, path = tempfile.mkstemp(suffix=".zip")
    with os.fdopen(fd, "wb") as fh:
        fh.write(buf.getvalue())
    return path


class ConverterTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.out = self._tmp.name
        self._zips = []
        self.addCleanup(self._tmp.cleanup)

    def tearDown(self):
        for path in self._zips:
            if os.path.exists(path):
                os.unlink(path)

    def zip_of(self, entries):
        path = make_zip(entries)
        self._zips.append(path)
        return path

    def run_cli(self, entries, target=None, out=None):
        argv = ["--input", self.zip_of(entries),
                "--output", out or self.out]
        if target:
            argv += ["--target", target]
        return cli.main(argv)

    def read_json(self, rel):
        with open(os.path.join(self.out, rel), encoding="utf-8") as fh:
            return json.load(fh)

    def warnings_codes(self):
        data = self.read_json("conversion-warnings.json")
        return [w["code"] for w in data["warnings"]]


class TestBasicParsing(ConverterTestCase):
    def test_basic_groups_and_fields(self):
        rc = self.run_cli({"037-906-024-AG.LBL": BASIC_LBL})
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        self.assertEqual(m["ecu_part_number"], "037906024AG")
        self.assertEqual(m["ecu_model_version"], 1)
        self.assertIn("001", m["groups"])
        g1 = m["groups"]["001"]
        self.assertEqual(g1["label"], "Engine data")
        self.assertEqual(len(g1["fields"]), 4)
        f1 = g1["fields"][0]
        self.assertEqual(f1["index"], 1)
        self.assertEqual(f1["name"], "Engine speed")
        self.assertEqual(f1["unit"], "rpm")
        self.assertEqual(f1["key"], "engine_speed")
        self.assertEqual(f1["formula"], {"type": "raw"})
        self.assertEqual(f1["confidence"], "medium")
        self.assertEqual(f1["notes"], "800-900")
        # unit heuristic: degree sign token
        self.assertEqual(g1["fields"][1]["unit"], "°C")
        self.assertIn("002", m["groups"])
        # source metadata
        self.assertEqual(m["source"]["type"], "lbl-import")
        self.assertEqual(m["source"]["confidence"], "medium")
        self.assertIn("037-906-024-AG.LBL", m["source"]["notes"])

    def test_comments_and_ignored_lines_counted(self):
        self.run_cli({"037-906-024-AG.LBL": BASIC_LBL})
        with open(os.path.join(self.out, "conversion-report.md"),
                  encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn("Comment lines: 2", text)
        # A01/C05/L1 lines ignored silently but counted
        self.assertIn("Non-measuring-block lines ignored: 3", text)

    def test_field_key_dedupe(self):
        lbl = "\n".join([
            "003.1,Temp Sensor,°C",
            "003.2,Temp Sensor,°C",
            "003.3,???",
            "",
        ])
        self.run_cli({"037-906-024-AG.LBL": lbl})
        m = self.read_json("vw/037906024AG.json")
        fields = m["groups"]["003"]["fields"]
        self.assertEqual(fields[0]["key"], "temp_sensor")
        self.assertEqual(fields[1]["key"], "temp_sensor_2")
        # non-sluggable name falls back to raw_GGG_F
        self.assertEqual(fields[2]["key"], "raw_003_3")


class TestEncoding(ConverterTestCase):
    def test_cp1252_file(self):
        # 0xB0 = degree sign in cp1252, invalid as UTF-8 start of sequence
        content = "001.1,Öltemperatur,°C\n".encode("cp1252")
        rc = self.run_cli({"037-906-024-AG.LBL": content})
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        f = m["groups"]["001"]["fields"][0]
        self.assertEqual(f["name"], "Öltemperatur")
        self.assertEqual(f["unit"], "°C")

    def test_utf8_bom_file(self):
        content = b"\xef\xbb\xbf001.1,Engine speed,rpm\n"
        rc = self.run_cli({"037-906-024-AG.LBL": content})
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        self.assertEqual(m["groups"]["001"]["fields"][0]["name"],
                         "Engine speed")

    def test_decode_fallback_order(self):
        self.assertEqual(parser.decode_tolerant(b"\xef\xbb\xbfabc")[1],
                         "utf-8-sig")
        self.assertEqual(parser.decode_tolerant("é°".encode("cp1252"))[1],
                         "cp1252")
        # cp1252-undecodable byte falls through to latin-1
        text, enc = parser.decode_tolerant(b"\x81\x8d")
        self.assertEqual(enc, "latin-1")
        self.assertEqual(len(text), 2)


class TestRedirects(ConverterTestCase):
    def test_redirect_resolution(self):
        rc = self.run_cli({
            "037-906-024-AG.LBL": "; synthetic\nREDIRECT,037-906-024-AB.LBL,hint\n",
            "037-906-024-AB.LBL": BASIC_LBL,
        })
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        self.assertIn("001", m["groups"])
        self.assertEqual(m["groups"]["001"]["fields"][0]["name"],
                         "Engine speed")
        # merged source metadata mentions the chain
        self.assertIn("037-906-024-AG.LBL", m["source"]["notes"])
        self.assertIn("037-906-024-AB.LBL", m["source"]["notes"])
        # redirect target file also produced its own model
        m2 = self.read_json("vw/037906024AB.json")
        self.assertEqual(m2["ecu_part_number"], "037906024AB")

    def test_redirect_case_insensitive(self):
        rc = self.run_cli({
            "037-906-024-AG.LBL": "REDIRECT,037-906-024-ab.lbl\n",
            "subdir/037-906-024-AB.LBL": BASIC_LBL,
        })
        self.assertEqual(rc, 0)
        self.assertTrue(os.path.exists(
            os.path.join(self.out, "vw/037906024AG.json")))

    def test_redirect_cycle_warning_no_crash(self):
        rc = self.run_cli({
            "037-906-024-AG.LBL": "REDIRECT,037-906-024-AB.LBL\n",
            "037-906-024-AB.LBL": "REDIRECT,037-906-024-AG.LBL\n",
        })
        self.assertEqual(rc, 0)
        self.assertIn("REDIRECT_CYCLE", self.warnings_codes())
        self.assertFalse(os.path.exists(
            os.path.join(self.out, "vw/037906024AG.json")))

    def test_redirect_missing_target_warning(self):
        rc = self.run_cli({
            "037-906-024-AG.LBL": "REDIRECT,DOES-NOT-EXIST.LBL\n",
        })
        self.assertEqual(rc, 0)
        self.assertIn("REDIRECT_TARGET_MISSING", self.warnings_codes())


class TestTolerance(ConverterTestCase):
    def test_unparsable_file_warns_but_does_not_crash(self):
        rc = self.run_cli({
            "037-906-024-AG.LBL": BASIC_LBL,
            "junk.LBL": b"\x00\x01\x02garbage without structure\xff\xfe",
        })
        self.assertEqual(rc, 0)
        codes = self.warnings_codes()
        self.assertIn("UNPARSABLE_FILE", codes)
        # good file still converted
        self.assertTrue(os.path.exists(
            os.path.join(self.out, "vw/037906024AG.json")))

    def test_invalid_part_number_filename_skipped_with_warning(self):
        rc = self.run_cli({
            "notes.LBL": BASIC_LBL,
            "037-906-024-AG.LBL": BASIC_LBL,
        })
        self.assertEqual(rc, 0)
        self.assertIn("INVALID_PART_NUMBER", self.warnings_codes())
        self.assertFalse(os.path.exists(
            os.path.join(self.out, "vw/NOTES.json")))


class TestTarget(ConverterTestCase):
    def test_target_exact_match_exit_0(self):
        rc = self.run_cli({"037-906-024-AG.LBL": BASIC_LBL},
                          target="037906024AG")
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        self.assertEqual(m["source"]["confidence"], "medium")
        self.assertNotIn("compatibility", m)

    def test_target_normalization(self):
        rc = self.run_cli({"037-906-024-AG.LBL": BASIC_LBL},
                          target="037-906-024-AG")
        self.assertEqual(rc, 0)

    def test_target_inferred_from_base_file(self):
        rc = self.run_cli({"037-906-024.LBL": BASE_LBL},
                          target="037906024AG")
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        self.assertEqual(m["ecu_part_number"], "037906024AG")
        self.assertEqual(m["source"]["confidence"], "low")
        inferred = m["compatibility"]["inferred_from"]
        self.assertEqual(inferred["ecu_part_number"], "037906024")
        self.assertEqual(inferred["file"], "037-906-024.LBL")
        for field in m["groups"]["001"]["fields"]:
            self.assertEqual(field["confidence"], "low")
        # base file itself is also emitted, at medium confidence
        base = self.read_json("vw/037906024.json")
        self.assertEqual(base["source"]["confidence"], "medium")

    def test_target_missing_exit_1(self):
        rc = self.run_cli({"811-906-264.LBL": BASE_LBL},
                          target="037906024AG")
        self.assertEqual(rc, 1)
        self.assertIn("TARGET_NOT_FOUND", self.warnings_codes())


class TestOutputs(ConverterTestCase):
    def test_index_json(self):
        rc = self.run_cli({
            "037-906-024-AG.LBL": BASIC_LBL,
            "037-906-024.LBL": BASE_LBL,
        })
        self.assertEqual(rc, 0)
        index = self.read_json("index.json")
        self.assertEqual(index["version"], 1)
        pns = [m["ecu_part_number"] for m in index["models"]]
        self.assertEqual(pns, ["037906024", "037906024AG"])
        for entry in index["models"]:
            self.assertIn("display_name", entry)
            self.assertIn("confidence", entry)
            self.assertTrue(entry["file"].startswith("vw/"))
            self.assertTrue(os.path.exists(
                os.path.join(self.out, entry["file"])))

    def test_reports_written(self):
        self.run_cli({"037-906-024-AG.LBL": BASIC_LBL})
        self.assertTrue(os.path.exists(
            os.path.join(self.out, "conversion-report.md")))
        data = self.read_json("conversion-warnings.json")
        self.assertEqual(data["version"], 1)
        self.assertIsInstance(data["warnings"], list)

    def test_generated_model_passes_validator(self):
        self.run_cli({"037-906-024-AG.LBL": BASIC_LBL})
        m = self.read_json("vw/037906024AG.json")
        self.assertEqual(model.validate_model(m), [])


class TestExitCodes(ConverterTestCase):
    def test_missing_input_exit_2(self):
        rc = cli.main(["--input", "/nonexistent/file.zip",
                       "--output", self.out])
        self.assertEqual(rc, 2)

    def test_not_a_zip_exit_2(self):
        fd, path = tempfile.mkstemp(suffix=".zip")
        self._zips.append(path)
        with os.fdopen(fd, "wb") as fh:
            fh.write(b"this is not a zip archive")
        rc = cli.main(["--input", path, "--output", self.out])
        self.assertEqual(rc, 2)

    def test_zip_without_lbl_exit_2(self):
        rc = self.run_cli({"readme.txt": "hello"})
        self.assertEqual(rc, 2)

    def test_unexpected_error_exit_3(self):
        original = cli.convert
        cli.convert = lambda *a, **k: (_ for _ in ()).throw(
            RuntimeError("boom"))
        try:
            rc = cli.main(["--input", "x.zip", "--output", self.out])
        finally:
            cli.convert = original
        self.assertEqual(rc, 3)


class TestModelHelpers(unittest.TestCase):
    def test_part_number_from_filename(self):
        self.assertEqual(
            model.part_number_from_filename("dir/037-906-024-AG.LBL"),
            "037906024AG")
        self.assertEqual(
            model.part_number_from_filename("037 906 024.lbl"),
            "037906024")
        self.assertIsNone(model.part_number_from_filename("notes.LBL"))
        self.assertIsNone(model.part_number_from_filename("übersicht.LBL"))

    def test_slugify(self):
        self.assertEqual(model.slugify("Engine speed"), "engine_speed")
        self.assertEqual(model.slugify("Öltemperatur (Öl)"),
                         "oltemperatur_ol")
        self.assertEqual(model.slugify("???"), "")

    def test_unit_heuristic(self):
        for unit in ("rpm", "°C", "%", "V", "ms", "km/h", "mbar"):
            self.assertTrue(parser.looks_like_unit(unit), unit)
        for not_unit in ("a very long spec text", "800-900", ""):
            self.assertFalse(parser.looks_like_unit(not_unit), not_unit)


if __name__ == "__main__":
    unittest.main()
