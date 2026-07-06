"""Warning collection and report writing for the LBL converter."""

import datetime
import json
import os


def make_warning(file, line, code, message, level="warning"):
    return {
        "file": file,
        "line": line,
        "code": code,
        "message": message,
        "level": level,
    }


def write_warnings_json(output_dir, warnings):
    path = os.path.join(output_dir, "conversion-warnings.json")
    payload = {"version": 1, "warnings": warnings}
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
    return path


def write_report_md(output_dir, summary, warnings):
    """Write conversion-report.md from a summary dict."""
    path = os.path.join(output_dir, "conversion-report.md")
    lines = []
    lines.append("# LBL Conversion Report")
    lines.append("")
    lines.append("Generated: %s" %
                 datetime.datetime.now(datetime.timezone.utc)
                 .strftime("%Y-%m-%dT%H:%M:%SZ"))
    lines.append("")
    lines.append("## Input")
    lines.append("")
    lines.append("- ZIP: `%s`" % summary.get("input_zip", ""))
    lines.append("- `.lbl` files discovered: %d"
                 % summary.get("files_discovered", 0))
    lines.append("")
    lines.append("## Parsing statistics")
    lines.append("")
    stats = summary.get("stats", {})
    lines.append("- Lines seen (non-empty): %d" % stats.get("lines", 0))
    lines.append("- Field lines parsed: %d" % stats.get("fields", 0))
    lines.append("- Group label lines: %d" % stats.get("group_labels", 0))
    lines.append("- Comment lines: %d" % stats.get("comments", 0))
    lines.append("- Redirect lines: %d" % stats.get("redirects", 0))
    lines.append("- Non-measuring-block lines ignored: %d"
                 % stats.get("ignored", 0))
    lines.append("")
    lines.append("## Models")
    lines.append("")
    models = summary.get("models", [])
    if models:
        for m in models:
            lines.append("- `%s` -> `%s` (confidence: %s)"
                         % (m["ecu_part_number"], m["file"],
                            m["confidence"]))
    else:
        lines.append("- none generated")
    lines.append("")
    target = summary.get("target")
    if target:
        lines.append("## Target")
        lines.append("")
        lines.append("- Requested: `%s`" % target)
        lines.append("- Status: %s" % summary.get("target_status", "missing"))
        lines.append("")
    lines.append("## Warnings (%d)" % len(warnings))
    lines.append("")
    if warnings:
        for w in warnings:
            loc = w["file"] or "-"
            if w.get("line"):
                loc += ":%d" % w["line"]
            level = w.get("level", "warning")
            tag = " _(info)_" if level == "info" else ""
            lines.append("- **%s**%s `%s` %s"
                         % (w["code"], tag, loc, w["message"]))
    else:
        lines.append("- none")
    lines.append("")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    return path
