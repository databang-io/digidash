# LBL Converter

CLI tool converting a user-provided user-provided label ZIP to Digifant Dashboard ECU Models.

Python 3 stdlib only — no pip dependencies.

## Invocation

Via the bundled executable:

```bash
tools/lbl-converter/lbl-converter --input ross_tech_labels.zip --output converted_ecu_models --target 037906024AG
```

Or as a Python module:

```bash
PYTHONPATH=tools/lbl-converter/src python3 -m lbl_converter \
  --input ross_tech_labels.zip --output converted_ecu_models --target 037906024AG
```

Arguments:

- `--input` (required): ZIP containing `.LBL` files (nested directories allowed).
- `--output` (required): output directory (created if missing).
- `--target` (optional): ECU part number to find or infer, in any form (`037906024AG`, `037-906-024-AG`, `037 906 024 AG`).

## Exit codes

- `0`: conversion successful, target model found/generated (or no target given)
- `1`: conversion completed but target model missing
- `2`: input invalid (missing file, not a ZIP, no `.lbl` entries)
- `3`: unexpected converter error

## Expected output

```text
converted_ecu_models/
  index.json
  vw/037906024AG.json
  conversion-report.md
  conversion-warnings.json
```

## Behavior notes

- Encoding fallback: UTF-8 (BOM aware) → Windows-1252 → ISO-8859-1; never crashes on undecodable bytes.
- Redirect lines (`REDIRECT,other.lbl,...`) are followed case-insensitively, with cycle detection and missing-target warnings.
- Only measuring-block lines are converted, in both the real VCDS format `G,F,name[,unit-or-name-part][,spec/notes]` (group 0-255, zero-padded or not; field 0-25; field 0 = group label) and the legacy `GGG.F,name[,unit][,extra]` format; other lines are ignored silently but counted in the report.
- The token after the field name becomes the unit only when it matches a unit heuristic (°C, %, V, ms, rpm, km/h, ...); a digit-free letters/spaces token is joined to the name (VCDS often splits names as `Coolant,Temperature`); remaining tokens are kept as `notes`.
- Wildcard hub files (`02E-300-0xx.lbl`, `1C0-920-x2x.lbl`) and addressing helpers (`00-01.lbl`) are parsed (they can be redirect hubs) but never yield a model; they are reported once, aggregated, at info level.
- Fields with index > 8 are parsed but excluded from models (the schema supports indexes 1-8), reported as a single aggregated info warning.
- Formulas are always `{"type": "raw"}` — no conversions are invented.
- Field confidence is `medium` for exact part-number label files, `low` for models inferred from a base file (e.g. target `037906024AG` inferred from `037-906-024.LBL`, recorded in `compatibility.inferred_from`).
- Generated models are structurally validated against the requirements of `schemas/ecu-model.schema.json`; failures become warnings and the model is excluded.

## Tests

```bash
cd tools/lbl-converter
python3 -m unittest discover
```

Test fixtures are synthetic `.lbl` files zipped on the fly — no real label-pack content is included in the repository.

See `SPEC.md` for full behavior.
