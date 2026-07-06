# 06 — Ross-Tech LBL Conversion

## Purpose

Convert a local user-provided Ross-Tech label ZIP into internal ECU Models.

## Legal/design boundary

The application must not ship original Ross-Tech `.LBL` files. The user can provide a ZIP from their own installation for local conversion. Generated ECU Model files should include source metadata but not large verbatim chunks of label file content beyond short field names/units necessary for user interpretation.

## Input

A ZIP file containing `.LBL` files, potentially with nested directories.

Examples of possible filenames:

```text
037-906-024-AG.LBL
037-906-024.LBL
037-906-024-AAM.LBL
```

## Output

```text
ecu_models/
  index.json
  vw/
    037906024AG.json
    ...
```

## Required converter module

```text
tools/lbl-converter/
  README.md
  SPEC.md
  src/...
```

Implementation can be Kotlin/JVM or Python for MVP. If Android project is Kotlin, Kotlin/JVM is cleaner long-term, but Python may be faster for first prototype.

## Parsing assumptions

Ross-Tech label syntax varies. The converter must be tolerant.

Expected patterns include:

- comments
- group labels
- measuring block descriptions
- field lines
- redirect lines
- part number references

Do not fail the whole conversion because one file is unparsable. Emit warnings.

## Redirect resolution

Label files can redirect to another label file. The converter shall:

1. index all filenames normalized case-insensitively
2. parse redirect targets
3. follow redirect chain
4. detect cycles
5. merge source metadata
6. write a warning if target is missing

## Conversion phases

1. Unzip to temp folder.
2. Discover `.LBL` files.
3. Read files with tolerant encoding strategy.
4. Parse metadata and possible part numbers.
5. Parse measuring block groups.
6. Resolve redirects.
7. Normalize to ECU Model.
8. Build index.
9. Validate JSON against schema.
10. Write summary report.

## Encoding

Try encodings in order:

- UTF-8 with BOM handling
- Windows-1252
- ISO-8859-1

Do not crash on replacement characters.

## Warnings report

Output:

```text
converted_ecu_models/conversion-report.md
converted_ecu_models/conversion-warnings.json
```

Warnings should include:

- file path
- line number if known
- warning code
- message

## Extracted data

For each group:

- group number
- group label if present
- field index
- field name
- unit
- raw comment
- confidence

## Formula policy

Label files may not contain true formulas. For MVP, set formula to `raw` unless a clear conversion rule is detected. Never invent conversions silently. Put uncertain mappings in `notes` and `confidence: low`.

## Special target priority

The converter must prioritize finding or generating a model for:

```text
037 906 024 AG
037906024AG
037-906-024-AG
```

If no exact label exists, it should look for broader `037-906-024` compatible files and generate a candidate model with `confidence: low` and `compatibility.inferred_from`.

## CLI contract

```bash
lbl-converter --input ross_tech_labels.zip --output converted_ecu_models --target 037906024AG
```

Exit codes:

- `0`: conversion successful, target model found/generated
- `1`: conversion completed but target model missing
- `2`: input invalid
- `3`: unexpected converter error

## Pseudo-code

```python
def convert(zip_path, output_dir, target=None):
    files = unzip_and_find_lbl(zip_path)
    parsed = []
    for file in files:
        text = read_tolerant(file)
        parsed.append(parse_lbl(text, file))
    resolved = resolve_redirects(parsed)
    models = [to_ecu_model(p) for p in resolved]
    index = build_index(models)
    validate_all(models, index)
    write_output(models, index, output_dir)
    write_report(models, warnings, output_dir)
```

## Validation

Every generated model must be validated against `schemas/ecu-model.schema.json`.

