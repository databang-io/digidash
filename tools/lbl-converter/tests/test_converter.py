"""Tests for the LBL -> ECU Model converter.

All .lbl fixtures are SYNTHETIC (invented labels/values), zipped on the
fly with zipfile. No real label-pack content is used.
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


# Synthetic label content in the real VCDS format (invented data).
BASIC_LBL = "\n".join([
    "; synthetic test label file",
    "; another comment line",
    "A01,Some address line",
    "C05,Coding hint line",
    "L1,Login hint line",
    "0,0,General data",
    "0,1,Undocumented,039",
    "1,0,Engine data",
    "1,1,Engine Speed,rpm,800-900",
    "1,2,Coolant,Temperature,must rise smoothly",
    "1,3,Battery Voltage,V,12.0-14.5",
    "1,4,Idle Switch State",
    "2,0,Mixture data",
    "2,1,Injection Time,ms",
    "2,2,Lambda,Value",
    "",
])

BASE_LBL = "\n".join([
    "; synthetic base label file",
    "1,0,Base engine data",
    "1,1,Engine Speed,rpm",
    "1,2,Coolant,Temperature",
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

    def warnings_list(self):
        return self.read_json("conversion-warnings.json")["warnings"]

    def warnings_codes(self):
        return [w["code"] for w in self.warnings_list()]


class TestBasicParsing(ConverterTestCase):
    def test_basic_groups_and_fields(self):
        rc = self.run_cli({"037-906-024-AG.LBL": BASIC_LBL})
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        self.assertEqual(m["ecu_part_number"], "037906024AG")
        self.assertEqual(m["ecu_model_version"], 1)
        # group 0 -> "000", numeric token2 goes to notes not name/unit
        g0 = m["groups"]["000"]
        self.assertEqual(g0["label"], "General data")
        self.assertEqual(g0["fields"][0]["name"], "Undocumented")
        self.assertEqual(g0["fields"][0]["unit"], "")
        self.assertEqual(g0["fields"][0]["notes"], "039")
        g1 = m["groups"]["001"]
        self.assertEqual(g1["label"], "Engine data")
        self.assertEqual(len(g1["fields"]), 4)
        f1 = g1["fields"][0]
        self.assertEqual(f1["index"], 1)
        self.assertEqual(f1["name"], "Engine Speed")
        self.assertEqual(f1["unit"], "rpm")
        self.assertEqual(f1["key"], "engine_speed")
        self.assertEqual(f1["formula"], {"type": "raw"})
        self.assertEqual(f1["confidence"], "medium")
        self.assertEqual(f1["notes"], "800-900")
        # two-part name split across comma tokens is joined
        f2 = g1["fields"][1]
        self.assertEqual(f2["name"], "Coolant Temperature")
        self.assertEqual(f2["key"], "coolant_temperature")
        self.assertEqual(f2["notes"], "must rise smoothly")
        self.assertEqual(g1["fields"][2]["unit"], "V")
        g2 = m["groups"]["002"]
        self.assertEqual(g2["fields"][0]["unit"], "ms")
        self.assertEqual(g2["fields"][1]["name"], "Lambda Value")
        # source metadata
        self.assertEqual(m["source"]["type"], "lbl-import")
        self.assertEqual(m["source"]["confidence"], "medium")
        self.assertIn("037-906-024-AG.LBL", m["source"]["notes"])

    def test_empty_token2_goes_to_notes(self):
        lbl = "1,1,Engine Speed,,Idle Specification 750-850 RPM\n"
        self.run_cli({"037-906-024-AG.LBL": lbl})
        f = self.read_json(
            "vw/037906024AG.json")["groups"]["001"]["fields"][0]
        self.assertEqual(f["name"], "Engine Speed")
        self.assertEqual(f["unit"], "")
        self.assertEqual(f["notes"], "Idle Specification 750-850 RPM")

    def test_zero_padded_group_numbers(self):
        lbl = "016,1,Some Value,rpm\n16,2,Other Value\n"
        self.run_cli({"037-906-024-AG.LBL": lbl})
        m = self.read_json("vw/037906024AG.json")
        self.assertEqual(list(m["groups"]), ["016"])
        self.assertEqual(len(m["groups"]["016"]["fields"]), 2)

    def test_legacy_dot_format_still_parsed(self):
        lbl = "\n".join([
            "001.0,Engine data",
            "001.1,Engine speed,rpm,800-900",
            "001.2,Coolant temperature,°C",
            "",
        ])
        rc = self.run_cli({"037-906-024-AG.LBL": lbl})
        self.assertEqual(rc, 0)
        g1 = self.read_json("vw/037906024AG.json")["groups"]["001"]
        self.assertEqual(g1["label"], "Engine data")
        self.assertEqual(g1["fields"][0]["name"], "Engine speed")
        self.assertEqual(g1["fields"][0]["unit"], "rpm")
        self.assertEqual(g1["fields"][1]["unit"], "°C")

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
            "3,1,Temp Sensor,°C",
            "3,2,Temp Sensor,°C",
            "3,3,???",
            "",
        ])
        self.run_cli({"037-906-024-AG.LBL": lbl})
        m = self.read_json("vw/037906024AG.json")
        fields = m["groups"]["003"]["fields"]
        self.assertEqual(fields[0]["key"], "temp_sensor")
        self.assertEqual(fields[1]["key"], "temp_sensor_2")
        # non-sluggable name falls back to raw_GGG_F
        self.assertEqual(fields[2]["key"], "raw_003_3")

    def test_high_field_indexes_parsed_but_excluded_from_model(self):
        # Indexes up to 10 are kept (group 000 has 10 raw fields on KWP1281);
        # anything above 10 is excluded with one aggregated info warning.
        lbl = "\n".join([
            "0,0,General data",
            "0,8,Value Eight",
            "0,9,Value Nine",
            "0,10,Value Ten",
            "0,11,Value Eleven",
            "",
        ])
        rc = self.run_cli({"037-906-024-AG.LBL": lbl})
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        indexes = [f["index"] for f in m["groups"]["000"]["fields"]]
        self.assertEqual(indexes, [8, 9, 10])
        w = [x for x in self.warnings_list()
             if x["code"] == "FIELD_INDEX_OUT_OF_RANGE"]
        self.assertEqual(len(w), 1)
        self.assertEqual(w[0]["level"], "info")
        self.assertIn("1 field(s)", w[0]["message"])


class TestEncoding(ConverterTestCase):
    def test_cp1252_file(self):
        # 0xB0 = degree sign in cp1252, invalid as UTF-8 start of sequence
        content = "1,1,Öltemperatur,°C\n".encode("cp1252")
        rc = self.run_cli({"037-906-024-AG.LBL": content})
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        f = m["groups"]["001"]["fields"][0]
        self.assertEqual(f["name"], "Öltemperatur")
        self.assertEqual(f["unit"], "°C")

    def test_utf8_bom_file(self):
        content = b"\xef\xbb\xbf1,1,Engine Speed,rpm\n"
        rc = self.run_cli({"037-906-024-AG.LBL": content})
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        self.assertEqual(m["groups"]["001"]["fields"][0]["name"],
                         "Engine Speed")

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
            "037-906-024-AG.LBL":
                "; synthetic\n"
                "REDIRECT,037-906-024-AB.LBL,H,T,AA;  PG engine code\n",
            "037-906-024-AB.LBL": BASIC_LBL,
        })
        self.assertEqual(rc, 0)
        m = self.read_json("vw/037906024AG.json")
        self.assertIn("001", m["groups"])
        self.assertEqual(m["groups"]["001"]["fields"][0]["name"],
                         "Engine Speed")
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

    def test_redirect_to_wildcard_hub(self):
        rc = self.run_cli({
            "01M-927-733.LBL": "REDIRECT,02E-300-0xx.LBL\n",
            "02E-300-0xx.LBL": BASIC_LBL,
        })
        self.assertEqual(rc, 0)
        # the concrete part number gets a model from the hub content
        m = self.read_json("vw/01M927733.json")
        self.assertIn("001", m["groups"])
        # the wildcard hub itself does not become a model
        self.assertFalse(os.path.exists(
            os.path.join(self.out, "vw/02E3000XX.json")))
        self.assertIn("NON_MODEL_FILE", self.warnings_codes())

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
        self.assertIn("UNPARSABLE_FILE", self.warnings_codes())
        # good file still converted
        self.assertTrue(os.path.exists(
            os.path.join(self.out, "vw/037906024AG.json")))

    def test_helper_and_wildcard_files_single_info_warning(self):
        rc = self.run_cli({
            "00-01.LBL": "; addressing helper\nREDIRECT,MISSING.LBL\n",
            "1C-01.LBL": BASE_LBL,
            "02E-300-0xx.LBL": BASE_LBL,
            "037-906-024-AG.LBL": BASIC_LBL,
        })
        self.assertEqual(rc, 0)
        infos = [w for w in self.warnings_list()
                 if w["code"] == "NON_MODEL_FILE"]
        # one aggregated info-level warning, not one per file
        self.assertEqual(len(infos), 1)
        self.assertEqual(infos[0]["level"], "info")
        self.assertIn("3 wildcard/helper", infos[0]["message"])
        # helper/wildcard files produced no models
        for pn in ("0001", "1C01", "02E3000XX"):
            self.assertFalse(os.path.exists(
                os.path.join(self.out, "vw/%s.json" % pn)), pn)
        self.assertTrue(os.path.exists(
            os.path.join(self.out, "vw/037906024AG.json")))


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
        self.assertIsNone(model.part_number_from_filename("00-01.lbl"))
        self.assertIsNone(model.part_number_from_filename("übersicht.LBL"))

    def test_is_wildcard_filename(self):
        self.assertTrue(model.is_wildcard_filename("02E-300-0xx.lbl"))
        self.assertTrue(model.is_wildcard_filename("1J0-919-xxx-17.lbl"))
        self.assertTrue(model.is_wildcard_filename("1Ux-919-xxx-17.lbl"))
        # lone lowercase x placeholders in mixed-case stems
        self.assertTrue(model.is_wildcard_filename("1C0-920-x2x.lbl"))
        self.assertTrue(model.is_wildcard_filename("1C0-919-95x.lbl"))
        self.assertFalse(model.is_wildcard_filename("01M-927-733.lbl"))
        self.assertFalse(model.is_wildcard_filename("037-906-024-AG.LBL"))
        # uppercase X is a real suffix letter, not a wildcard
        self.assertFalse(model.is_wildcard_filename("022-906-032-BMX.lbl"))
        # all-lowercase filenames are not treated as wildcards
        self.assertFalse(model.is_wildcard_filename("022-906-032-bmx.lbl"))

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
