"""CLI entry point and conversion pipeline.

Exit codes (see SPEC.md):
    0  conversion successful, target model found/generated (or no target)
    1  conversion completed but target model missing
    2  input invalid
    3  unexpected converter error
"""

import argparse
import json
import os
import sys
import zipfile

from . import model as model_mod
from . import parser as parser_mod
from . import report as report_mod

EXIT_OK = 0
EXIT_TARGET_MISSING = 1
EXIT_INPUT_INVALID = 2
EXIT_UNEXPECTED = 3


class InputError(Exception):
    """Invalid input (maps to exit code 2)."""


def _discover_lbl_entries(zf):
    entries = []
    for info in zf.infolist():
        if info.is_dir():
            continue
        if info.filename.lower().endswith(".lbl"):
            entries.append(info.filename)
    return sorted(entries)


def _chain_notes(chain):
    return " -> ".join(p.basename for p in chain)


def convert(input_zip, output_dir, target=None):
    """Run the full conversion. Returns the process exit code."""
    if not os.path.isfile(input_zip):
        raise InputError("input ZIP not found: %s" % input_zip)
    if not zipfile.is_zipfile(input_zip):
        raise InputError("input is not a valid ZIP archive: %s" % input_zip)

    warnings = []
    parsed_files = []

    with zipfile.ZipFile(input_zip) as zf:
        entries = _discover_lbl_entries(zf)
        if not entries:
            raise InputError("no .lbl files found in %s" % input_zip)
        for entry in entries:
            data = zf.read(entry)
            text, encoding = parser_mod.decode_tolerant(data)
            parsed = parser_mod.parse_lbl(text, entry, warnings)
            parsed.encoding = encoding
            if not parsed.has_content and not parsed.redirects:
                warnings.append(report_mod.make_warning(
                    entry, None, "UNPARSABLE_FILE",
                    "No measuring-block content or redirect found "
                    "(%d line(s) ignored)" % parsed.stats["ignored"]))
            parsed_files.append(parsed)

    resolved, chains = parser_mod.resolve_redirects(parsed_files, warnings)

    # Build one model per file whose name is a valid VAG part number.
    # Wildcard hubs (e.g. 02E-300-0xx.lbl) and addressing helpers (e.g.
    # 00-01.lbl) are parsed (they may be redirect hubs) but never yield
    # a model; they are reported once, aggregated, at info level.
    models = {}          # part number -> model dict
    model_files = {}     # part number -> originating file path in the zip
    non_model_files = []  # wildcard/helper files skipped for model gen
    skipped_field_total = 0
    skipped_field_files = 0
    for parsed in parsed_files:
        part_number = model_mod.part_number_from_filename(parsed.path)
        if part_number is None or \
                model_mod.is_wildcard_filename(parsed.path):
            non_model_files.append(parsed.basename)
            continue
        effective = resolved.get(parsed.path)
        if effective is None or not effective.has_content:
            if effective is not None and not effective.has_content:
                warnings.append(report_mod.make_warning(
                    parsed.path, None, "NO_CONTENT",
                    "No measuring-block groups after redirect resolution; "
                    "no model generated"))
            continue
        if part_number in models:
            warnings.append(report_mod.make_warning(
                parsed.path, None, "DUPLICATE_PART_NUMBER",
                "Part number %s already generated from %s; skipping"
                % (part_number, model_files[part_number])))
            continue

        chain = chains.get(parsed.path, [parsed])
        notes = _chain_notes(chain)
        ecu_model, skipped_fields = model_mod.build_model(
            part_number, effective, "medium", notes)
        if skipped_fields:
            skipped_field_total += skipped_fields
            skipped_field_files += 1

        errors = model_mod.validate_model(ecu_model)
        if errors:
            warnings.append(report_mod.make_warning(
                parsed.path, None, "SCHEMA_VALIDATION_FAILED",
                "Model %s excluded: %s" % (part_number, "; ".join(errors))))
            continue
        models[part_number] = ecu_model
        model_files[part_number] = parsed.path

    if non_model_files:
        warnings.append(report_mod.make_warning(
            None, None, "NON_MODEL_FILE",
            "%d wildcard/helper label file(s) parsed without model "
            "generation (e.g. %s)"
            % (len(non_model_files),
               ", ".join(sorted(non_model_files)[:5])),
            level="info"))
    if skipped_field_total:
        warnings.append(report_mod.make_warning(
            None, None, "FIELD_INDEX_OUT_OF_RANGE",
            "%d field(s) with index > 10 across %d file(s) excluded from "
            "models (schema supports indexes 1-10)"
            % (skipped_field_total, skipped_field_files),
            level="info"))

    # Target handling: exact match, else base-file fallback.
    target_norm = None
    target_status = None
    if target:
        target_norm = model_mod.normalize_part_number(target)
        if target_norm in models:
            target_status = "found (exact label file)"
        else:
            base_pn = None
            for pn in models:
                if (target_norm.startswith(pn) and pn != target_norm
                        and (base_pn is None or len(pn) > len(base_pn))):
                    base_pn = pn
            if base_pn is not None:
                inferred = model_mod.derive_inferred_model(
                    models[base_pn], target_norm, model_files[base_pn])
                errors = model_mod.validate_model(inferred)
                if errors:
                    warnings.append(report_mod.make_warning(
                        model_files[base_pn], None,
                        "SCHEMA_VALIDATION_FAILED",
                        "Inferred model %s excluded: %s"
                        % (target_norm, "; ".join(errors))))
                    target_status = "missing"
                else:
                    models[target_norm] = inferred
                    model_files[target_norm] = model_files[base_pn]
                    target_status = ("generated (inferred from base file %s)"
                                     % model_files[base_pn])
                    warnings.append(report_mod.make_warning(
                        model_files[base_pn], None, "TARGET_INFERRED",
                        "Target %s inferred from base part number %s "
                        "with confidence low" % (target_norm, base_pn)))
            else:
                target_status = "missing"
                warnings.append(report_mod.make_warning(
                    None, None, "TARGET_NOT_FOUND",
                    "No exact or base label file found for target %s"
                    % target_norm))

    # Write output.
    vw_dir = os.path.join(output_dir, "vw")
    os.makedirs(vw_dir, exist_ok=True)

    index_models = []
    for pn in sorted(models):
        rel = "vw/%s.json" % pn
        with open(os.path.join(output_dir, rel), "w", encoding="utf-8") as fh:
            json.dump(models[pn], fh, indent=2, ensure_ascii=False)
            fh.write("\n")
        index_models.append({
            "ecu_part_number": pn,
            "display_name": models[pn]["display_name"],
            "file": rel,
            "confidence": models[pn]["source"]["confidence"],
        })

    index = {"version": 1, "models": index_models}
    with open(os.path.join(output_dir, "index.json"), "w",
              encoding="utf-8") as fh:
        json.dump(index, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    total_stats = {}
    for parsed in parsed_files:
        for key, value in parsed.stats.items():
            total_stats[key] = total_stats.get(key, 0) + value

    summary = {
        "input_zip": input_zip,
        "files_discovered": len(parsed_files),
        "stats": total_stats,
        "models": index_models,
        "target": target_norm,
        "target_status": target_status,
    }
    report_mod.write_warnings_json(output_dir, warnings)
    report_mod.write_report_md(output_dir, summary, warnings)

    if target_norm and target_norm not in models:
        return EXIT_TARGET_MISSING
    return EXIT_OK


def build_arg_parser():
    parser = argparse.ArgumentParser(
        prog="lbl-converter",
        description="Convert a Ross-Tech label ZIP into ECU Model JSON "
                    "files.")
    parser.add_argument("--input", required=True,
                        help="path to the ZIP containing .LBL files")
    parser.add_argument("--output", required=True,
                        help="output directory for models, index and "
                             "reports")
    parser.add_argument("--target", default=None,
                        help="ECU part number to find or infer "
                             "(e.g. 037906024AG)")
    return parser


def main(argv=None):
    args = build_arg_parser().parse_args(argv)
    try:
        return convert(args.input, args.output, args.target)
    except InputError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return EXIT_INPUT_INVALID
    except Exception as exc:  # noqa: BLE001 - contract: exit 3
        print("unexpected error: %s: %s" % (type(exc).__name__, exc),
              file=sys.stderr)
        return EXIT_UNEXPECTED


if __name__ == "__main__":
    sys.exit(main())
