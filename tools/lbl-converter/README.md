# LBL Converter

CLI tool converting a user-provided Ross-Tech label ZIP to Digifant Dashboard ECU Models.

## CLI

```bash
lbl-converter --input ross_tech_labels.zip --output converted_ecu_models --target 037906024AG
```

## Expected output

```text
converted_ecu_models/
  index.json
  vw/037906024AG.json
  conversion-report.md
  conversion-warnings.json
```

See `SPEC.md` for full behavior.

