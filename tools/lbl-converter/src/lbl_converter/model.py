"""ECU Model construction and structural validation.

Formula policy: always ``{"type": "raw"}`` — the converter never invents
conversions. Field confidence is ``medium`` for exact part-number label
files and ``low`` for inferred/base-file models.
"""

import copy
import os
import re
import unicodedata

PART_NUMBER_RE = re.compile(r"^[0-9A-Z]{9,14}$")

MODEL_VERSION = 1


def part_number_from_filename(filename):
    """Derive a normalized VAG part number from a label filename.

    ``037-906-024-AG.LBL`` -> ``037906024AG``. Returns None when the
    result does not look like a VAG part number.
    """
    base = os.path.basename(filename)
    stem = os.path.splitext(base)[0]
    candidate = re.sub(r"[-\s_]+", "", stem).upper()
    if PART_NUMBER_RE.match(candidate):
        return candidate
    return None


def normalize_part_number(value):
    return re.sub(r"[-\s_]+", "", value or "").upper()


def is_wildcard_filename(filename):
    """True for wildcard label filenames like ``02E-300-0xx.lbl``.

    Ross-Tech uses lowercase ``x`` (single or runs, e.g. ``1C0-920-x2x``)
    and ``?`` as placeholders in hub files that cover several part
    numbers; such files must not yield a model of their own. A lone
    lowercase ``x`` only counts as a placeholder when the stem is
    otherwise mixed-case (real suffix letters are uppercase, e.g.
    ``022-906-032-BMX``).
    """
    stem = os.path.splitext(os.path.basename(filename))[0]
    if re.search(r"xx|\?", stem, re.IGNORECASE):
        return True
    return "x" in stem and stem != stem.lower()


def slugify(name):
    """ASCII lowercase underscore slug of a field name; '' if nothing left."""
    text = unicodedata.normalize("NFKD", name)
    text = text.encode("ascii", "ignore").decode("ascii").lower()
    text = re.sub(r"[^a-z0-9]+", "_", text).strip("_")
    return text


def build_model(part_number, parsed, confidence, source_notes,
                inferred_from=None):
    """Build an ECU Model dict from a resolved ParsedLbl.

    Returns ``(model, skipped_fields)`` where ``skipped_fields`` counts
    parsed fields with index > 10 that the schema cannot represent
    (group 000 carries 10 raw fields on KWP1281 ECUs).
    """
    groups = {}
    skipped_fields = 0
    for group_num in sorted(parsed.groups):
        pgroup = parsed.groups[group_num]
        gkey = "%03d" % group_num
        fields = []
        key_counts = {}
        for idx in sorted(pgroup.fields):
            if idx > 10:
                skipped_fields += 1
                continue
            name, unit, extra = pgroup.fields[idx]
            base_key = slugify(name) or "raw_%s_%d" % (gkey, idx)
            count = key_counts.get(base_key, 0) + 1
            key_counts[base_key] = count
            key = base_key if count == 1 else "%s_%d" % (base_key, count)
            field = {
                "index": idx,
                "key": key,
                "name": name or "Raw field %s/%d" % (gkey, idx),
                "unit": unit,
                "value_type": "unknown",
                "formula": {"type": "raw"},
                "confidence": confidence,
            }
            if extra:
                field["notes"] = extra
            fields.append(field)
        group = {"fields": fields}
        if pgroup.label:
            group["label"] = pgroup.label
        groups[gkey] = group

    model = {
        "ecu_model_version": MODEL_VERSION,
        "ecu_part_number": part_number,
        "display_name": "ECU %s" % part_number,
        "protocol": "unknown",
        "source": {
            "type": "lbl-import",
            "confidence": confidence,
            "notes": source_notes,
        },
        "groups": groups,
    }
    if inferred_from:
        model["compatibility"] = {"inferred_from": inferred_from}
    return model, skipped_fields


def derive_inferred_model(base_model, target_part_number, base_file):
    """Clone a base-file model for an inferred target part number.

    Confidence drops to ``low`` everywhere and compatibility records the
    inference source.
    """
    model = copy.deepcopy(base_model)
    model["ecu_part_number"] = target_part_number
    model["display_name"] = "ECU %s" % target_part_number
    model["source"] = {
        "type": "lbl-import",
        "confidence": "low",
        "notes": "Inferred from base label file %s (part number %s)"
                 % (base_file, base_model["ecu_part_number"]),
    }
    model["compatibility"] = {
        "inferred_from": {
            "ecu_part_number": base_model["ecu_part_number"],
            "file": base_file,
        }
    }
    for group in model["groups"].values():
        for field in group["fields"]:
            field["confidence"] = "low"
    return model


def validate_model(model):
    """Minimal structural validation against schemas/ecu-model.schema.json.

    Returns a list of error strings (empty when valid). Stdlib only —
    intentionally not a generic JSON Schema implementation.
    """
    errors = []
    required = ("ecu_model_version", "ecu_part_number", "display_name",
                "protocol", "groups", "source")
    for key in required:
        if key not in model:
            errors.append("missing required key %r" % key)
    if errors:
        return errors

    if not isinstance(model["ecu_model_version"], int) \
            or model["ecu_model_version"] < 1:
        errors.append("ecu_model_version must be an integer >= 1")
    if not re.match(r"^[A-Z0-9]+$", str(model["ecu_part_number"])):
        errors.append("ecu_part_number must match ^[A-Z0-9]+$")
    if not isinstance(model["display_name"], str):
        errors.append("display_name must be a string")
    if not isinstance(model["protocol"], str):
        errors.append("protocol must be a string")

    source = model["source"]
    if not isinstance(source, dict) or "type" not in source:
        errors.append("source must be an object with a 'type' key")
    elif source.get("confidence") not in (None, "high", "medium", "low"):
        errors.append("source.confidence must be high/medium/low")

    groups = model["groups"]
    if not isinstance(groups, dict):
        errors.append("groups must be an object")
        return errors
    for gkey, group in groups.items():
        prefix = "groups[%s]" % gkey
        if not re.match(r"^\d{3}$", gkey):
            errors.append("%s: key must be a 3-digit group number" % prefix)
        if not isinstance(group, dict) or \
                not isinstance(group.get("fields"), list):
            errors.append("%s: must have a 'fields' array" % prefix)
            continue
        for i, field in enumerate(group["fields"]):
            fprefix = "%s.fields[%d]" % (prefix, i)
            if not isinstance(field, dict):
                errors.append("%s: must be an object" % fprefix)
                continue
            for fkey in ("index", "key", "name", "formula"):
                if fkey not in field:
                    errors.append("%s: missing %r" % (fprefix, fkey))
            idx = field.get("index")
            if not isinstance(idx, int) or not 1 <= idx <= 10:
                errors.append("%s: index must be an integer 1-10" % fprefix)
            formula = field.get("formula")
            if not isinstance(formula, dict) or "type" not in formula:
                errors.append("%s: formula must be an object with 'type'"
                              % fprefix)
            if field.get("confidence") not in (None, "high", "medium", "low"):
                errors.append("%s: confidence must be high/medium/low"
                              % fprefix)
    return errors
