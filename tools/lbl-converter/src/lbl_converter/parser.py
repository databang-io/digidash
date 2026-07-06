"""Tolerant parser for Ross-Tech VCDS ``.LBL`` label files.

Only measuring-block lines are extracted:

    GGG.F,text[,unit][,extra...]

where ``GGG`` is a 1-3 digit group number and ``F`` a field index.
``F == 0`` means the line carries the group label. Everything else
(``A*``, ``C*``, ``L*`` prefixed lines, free text, ...) is ignored
silently but counted so the report can mention it.
"""

import os
import re

from . import report

# Encoding fallback order per SPEC: UTF-8 (BOM aware) -> cp1252 -> latin-1.
_ENCODINGS = ("utf-8-sig", "cp1252")

GROUP_LINE_RE = re.compile(r"^\s*(\d{1,3})\.(\d{1,2})\s*,(.*)$")
REDIRECT_RE = re.compile(r"^\s*REDIRECT\s*,\s*([^,;]+?)\s*(?:[,;].*)?$", re.IGNORECASE)

# Heuristic unit detection for the token following the field name.
_UNIT_EXACT_RE = re.compile(
    r"^(?:"
    r"°?[CF]|%|V|mV|kV|A|mA|ms|s|sec|min|h|rpm|1/min|/min|U/min|"
    r"km/h|mph|km|m|mm|bar|mbar|kPa|hPa|Pa|MPa|psi|"
    r"g/s|g/h|kg/h|mg/h|mg/stk|l/h|l/100km|Hz|kHz|Nm|"
    r"deg|°|°KW|°CRK|°BTDC|°ATDC|Ohm|kOhm|lambda"
    r")$",
    re.IGNORECASE,
)


class ParsedGroup:
    def __init__(self):
        self.label = None
        # field index -> (name, unit, extra)
        self.fields = {}


class ParsedLbl:
    """Result of parsing one .lbl file from the input ZIP."""

    def __init__(self, path):
        self.path = path
        self.basename = os.path.basename(path)
        self.encoding = None
        self.groups = {}          # int group number -> ParsedGroup
        self.redirects = []       # redirect target basenames (as written)
        self.stats = {
            "lines": 0,
            "comments": 0,
            "fields": 0,
            "group_labels": 0,
            "redirects": 0,
            "ignored": 0,
        }

    @property
    def has_content(self):
        return bool(self.groups)


def decode_tolerant(data):
    """Decode raw bytes trying UTF-8 (BOM aware), cp1252, then latin-1.

    Never raises: latin-1 with replacement characters is the terminal
    fallback, so any byte sequence decodes.
    """
    for enc in _ENCODINGS:
        try:
            return data.decode(enc), enc
        except UnicodeDecodeError:
            continue
    return data.decode("latin-1", errors="replace"), "latin-1"


def looks_like_unit(token):
    token = token.strip()
    if not token or len(token) > 8:
        return False
    if _UNIT_EXACT_RE.match(token):
        return True
    # Anything short containing a degree/percent/ohm sign is a unit.
    return any(ch in token for ch in ("°", "%", "Ω", "µ"))


def parse_lbl(text, path, warnings):
    """Parse label file text into a ParsedLbl. Never raises on content."""
    parsed = ParsedLbl(path)
    for lineno, raw_line in enumerate(text.splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        parsed.stats["lines"] += 1

        if line.startswith(";"):
            parsed.stats["comments"] += 1
            continue

        m = REDIRECT_RE.match(line)
        if m:
            parsed.stats["redirects"] += 1
            parsed.redirects.append(m.group(1).strip())
            continue

        m = GROUP_LINE_RE.match(line)
        if not m:
            # Non measuring-block line (A*, C*, L* prefixes, free text...).
            parsed.stats["ignored"] += 1
            continue

        group_num = int(m.group(1))
        field_idx = int(m.group(2))
        rest = m.group(3)

        group = parsed.groups.setdefault(group_num, ParsedGroup())

        if field_idx == 0:
            label = rest.split(",", 1)[0].strip()
            if group.label and label and group.label != label:
                warnings.append(report.make_warning(
                    path, lineno, "DUPLICATE_GROUP_LABEL",
                    "Group %03d already has label %r; keeping first"
                    % (group_num, group.label)))
            elif label:
                group.label = label
            parsed.stats["group_labels"] += 1
            continue

        if field_idx > 8:
            warnings.append(report.make_warning(
                path, lineno, "FIELD_INDEX_OUT_OF_RANGE",
                "Field index %d out of range 1-8 in group %03d; line skipped"
                % (field_idx, group_num)))
            continue

        tokens = rest.split(",")
        name = tokens[0].strip()
        unit = ""
        extra_tokens = tokens[1:]
        if extra_tokens and looks_like_unit(extra_tokens[0]):
            unit = extra_tokens[0].strip()
            extra_tokens = extra_tokens[1:]
        extra = ",".join(t.strip() for t in extra_tokens).strip(", ")

        if field_idx in group.fields:
            warnings.append(report.make_warning(
                path, lineno, "DUPLICATE_FIELD",
                "Field %03d.%d already defined; keeping first"
                % (group_num, field_idx)))
            continue

        group.fields[field_idx] = (name, unit, extra)
        parsed.stats["fields"] += 1

    return parsed


def resolve_redirects(parsed_files, warnings):
    """Resolve redirect chains between parsed files.

    Filenames are matched case-insensitively on basename. Returns
    ``(resolved, chains)`` where ``resolved`` maps each file path to its
    *effective* ParsedLbl (the end of its redirect chain) or None if the
    chain is broken (missing target or cycle), and ``chains`` maps file
    paths to the list of ParsedLbl objects traversed (for source
    metadata merging). A file with its own group content is its own
    resolution.
    """
    by_name = {}
    for p in parsed_files:
        by_name.setdefault(p.basename.lower(), p)

    resolved = {}
    chains = {}
    for p in parsed_files:
        if p.has_content or not p.redirects:
            resolved[p.path] = p
            chains[p.path] = [p]
            continue

        chain = [p]
        seen = {p.basename.lower()}
        current = p
        effective = None
        while True:
            target_name = os.path.basename(current.redirects[0]).lower()
            target = by_name.get(target_name)
            if target is None:
                warnings.append(report.make_warning(
                    p.path, None, "REDIRECT_TARGET_MISSING",
                    "Redirect target %r not found in archive"
                    % current.redirects[0]))
                break
            if target.basename.lower() in seen:
                warnings.append(report.make_warning(
                    p.path, None, "REDIRECT_CYCLE",
                    "Redirect cycle detected: %s -> %s"
                    % (" -> ".join(c.basename for c in chain),
                       target.basename)))
                break
            chain.append(target)
            seen.add(target.basename.lower())
            if target.has_content or not target.redirects:
                effective = target
                break
            current = target

        resolved[p.path] = effective
        if effective is not None:
            # Record the chain so source metadata can be merged.
            resolved[p.path + "\0chain"] = chain + []
    return resolved
