# 15 — Data Dictionary

## EcuIdentity

- `partNumberRaw`: raw text from ECU
- `partNumberNormalized`: normalized string
- `component`: component/system text
- `serialNumber`: serial if available
- `protocol`: protocol if known

## RawMeasuringBlock

- `group`: integer group number
- `fields`: list of raw field values
- `timestamp`: app timestamp
- `source`: fake/deepobd

## RawField

- `index`: 1-based field index
- `rawString`: raw display string
- `rawNumeric`: parsed numeric if safe
- `unitHint`: optional backend unit

## InterpretedMeasurement

- `key`: stable internal key
- `name`: display name
- `value`: typed value
- `unit`: unit string
- `status`: normal/warning/critical/unavailable/unknown/stale
- `confidence`: high/medium/low
- `sourceGroup`: group number
- `sourceField`: field index

## DashboardCard

- `key`
- `title`
- `valueText`
- `unit`
- `status`
- `updatedAt`
- `message`

