"""Tolerant parser for Ross-Tech VCDS ``.LBL`` label files.

Only measuring-block lines are extracted. Two line formats are
accepted:

    G,F,name[,unit-or-name-part][,spec/notes...]   (real VCDS format)
    GGG.F,name[,unit][,extra...]                   (legacy format)

``G`` is the group number (0-255, zero-padded or not) and ``F`` the
field index (0-25). ``F == 0`` means the line carries the group label.
The token after the name is treated as a unit only when it matches the
unit heuristic; when it looks like a name fragment (letters/spaces, no
digits) it is joined to the name with a space — VCDS labels often split
a two-part name across two comma tokens (``Coolant,Temperature``).
Remaining tokens are kept as free-text notes. Everything else (``A*``,
``C*``, ``L*`` prefixed lines, free text, ...) is ignored silently but
counted so the report can mention it.
"""

import os
import re

from . import report

# Encoding fallback order per SPEC: UTF-8 (BOM aware) -> cp1252 -> latin-1.
_ENCODINGS = ("utf-8-sig", "cp1252")

# Real VCDS format: G,F,rest (group may be zero-padded: 0,3 / 016,1)
GROUP_LINE_RE = re.compile(r"^\s*(\d{1,3})\s*,\s*(\d{1,2})\s*,(.*)$")
# Legacy format: GGG.F,rest
LEGACY_GROUP_LINE_RE = re.compile(r"^\s*(\d{1,3})\.(\d{1,2})\s*,(.*)$")
REDIRECT_RE = re.compile(r"^\s*REDIRECT\s*,\s*([^,;]+?)\s*(?:[,;].*)?$", re.IGNORECASE)

MAX_GROUP = 255
MAX_FIELD = 25

# Name fragments: letters/spaces (plus light punctuation), no digits.
_NAME_FRAGMENT_RE = re.compile(r"^[A-Za-z][A-Za-z .()'/&+-]*$")

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

        m = GROUP_LINE_RE.match(line) or LEGACY_GROUP_LINE_RE.match(line)
        if not m:
            # Non measuring-block line (A*, C*, L* prefixes, free text...).
            parsed.stats["ignored"] += 1
            continue

        group_num = int(m.group(1))
        field_idx = int(m.group(2))
        rest = m.group(3)

        if group_num > MAX_GROUP or field_idx > MAX_FIELD:
            parsed.stats["ignored"] += 1
            continue

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

        tokens = rest.split(",")
        name = tokens[0].strip()
        unit = ""
        extra_tokens = tokens[1:]
        if extra_tokens:
            token2 = extra_tokens[0].strip()
            if looks_like_unit(token2):
                unit = token2
                extra_tokens = extra_tokens[1:]
            elif token2 and not any(ch.isdigit() for ch in token2) \
                    and _NAME_FRAGMENT_RE.match(token2):
                # Two-part name split across comma tokens.
                name = ("%s %s" % (name, token2)).strip()
                extra_tokens = extra_tokens[1:]
        extra = ", ".join(
            t for t in (tok.strip() for tok in extra_tokens) if t)

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
        chains[p.path] = chain
    return resolved, chains
