# ECU Model Validator

Validates ECU Model JSON files against `schemas/ecu-model.schema.json` and additional semantic rules.

## Semantic validation rules

- part number normalized format
- group keys are 3-digit strings
- field indexes unique per group
- formula types known
- threshold ranges do not conflict
- dashboard keys unique
- target model `037906024AG` exists in index

