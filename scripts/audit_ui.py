#!/usr/bin/env python3
"""Static audit of a single-file HTML prototype against the Vercel Web Interface Guidelines.

Usage: python3 audit_ui.py <file.html>
Outputs terse `file:line - issue` findings, grouped by severity.
"""
import re
import sys
from pathlib import Path

CHECKS = []


def check(name, severity):
    def deco(fn):
        CHECKS.append((name, severity, fn))
        return fn

    return deco


@check("animation", "fail")
def transition_all(text, lines):
    return [(i, "transition: all - list properties explicitly")
            for i, l in enumerate(lines, 1) if re.search(r"transition\s*:\s*all", l)]


@check("focus", "fail")
def outline_none(text, lines):
    out = []
    has_focus_visible = ":focus-visible" in text
    for i, l in enumerate(lines, 1):
        if re.search(r"outline\s*:\s*(none|0)", l) and not has_focus_visible:
            out.append((i, "outline:none without a :focus-visible replacement"))
    if not has_focus_visible:
        out.append((0, "no :focus-visible rule anywhere - keyboard focus invisible"))
    return out


@check("a11y", "fail")
def icon_buttons_no_label(text, lines):
    out = []
    for i, l in enumerate(lines, 1):
        for m in re.finditer(r"<button\b[^>]*>", l):
            tag = m.group(0)
            if "aria-label" in tag or "title=" in tag:
                continue
            # find the button content on the same or next lines
            rest = l[m.end():]
            stripped = re.sub(r"<[^>]+>", "", rest).strip()
            if stripped in ("", "</button>") or stripped.startswith("</button>"):
                out.append((i, "icon-only button without aria-label"))
    return out


@check("a11y", "fail")
def clickable_divs(text, lines):
    out = []
    for i, l in enumerate(lines, 1):
        if re.search(r"<(div|span|li)\b[^>]*onclick=", l, re.I):
            out.append((i, "click handler on a non-interactive element - use <button>"))
    return out


@check("motion", "fail")
def reduced_motion(text, lines):
    if "prefers-reduced-motion" not in text:
        return [(0, "no prefers-reduced-motion media query")]
    return []


@check("motion", "warn")
def animation_props(text, lines):
    out = []
    for i, l in enumerate(lines, 1):
        for prop in ("height", "width", "top", "left", "margin"):
            if re.search(r"@keyframes[^{]*\{[^}]*" + prop + r"\s*:", l):
                out.append((i, f"@keyframes animates `{prop}` - prefer transform/opacity"))
    return out


@check("typography", "warn")
def ellipsis(text, lines):
    return [(i, 'three dots "..." - use the ellipsis character')
            for i, l in enumerate(lines, 1)
            if re.search(r">[^<]*\.\.\.[^<]*<", l)]


@check("typography", "warn")
def tabular_nums(text, lines):
    if re.search(r"class=\"timer|class='timer|\.timer\b", text) and "tabular-nums" not in text:
        return [(0, "timer without font-variant-numeric: tabular-nums - digits will shift")]
    return []


@check("typography", "warn")
def text_wrap_balance(text, lines):
    heads = [i for i, l in enumerate(lines, 1) if re.search(r"<h1\b|<h2\b", l)]
    if heads and "text-wrap" not in text:
        return [(heads[0], "headings without text-wrap: balance - widows possible")]
    return []


@check("a11y", "warn")
def aria_live(text, lines):
    if re.search(r"transcript|live", text, re.I) and "aria-live" not in text:
        return [(0, "live-updating transcript without aria-live - screen readers stay silent")]
    return []


@check("a11y", "warn")
def headings_order(text, lines):
    levels = [(i, int(m.group(1))) for i, l in enumerate(lines, 1)
              for m in re.finditer(r"<h([1-6])\b", l)]
    out, prev = [], 0
    for i, lv in levels:
        if prev and lv > prev + 1:
            out.append((i, f"heading jumps h{prev} to h{lv}"))
        prev = lv
    if levels and levels[0][1] != 1:
        out.append((levels[0][0], "first heading is not h1"))
    return out


@check("layout", "warn")
def overscroll(text, lines):
    if "overflow-y:auto" in text.replace(" ", "") and "overscroll-behavior" not in text:
        return [(0, "scroll containers without overscroll-behavior: contain")]
    return []


@check("touch", "warn")
def touch_action(text, lines):
    if "touch-action" not in text:
        return [(0, "no touch-action: manipulation on controls")]
    return []


@check("meta", "info")
def meta_basics(text, lines):
    out = []
    if not re.search(r"<html[^>]*lang=", text):
        out.append((0, "<html> without lang attribute"))
    if not re.search(r"<title>", text):
        out.append((0, "missing <title>"))
    if re.search(r"user-scalable\s*=\s*no|maximum-scale\s*=\s*1", text):
        out.append((0, "viewport disables zoom"))
    return out


@check("content", "info")
def empty_states(text, lines):
    if "lorem ipsum" in text.lower():
        return [(0, "lorem ipsum in visible content")]
    return []


def main(path):
    text = Path(path).read_text(encoding="utf-8")
    lines = text.splitlines()
    results = {}
    for name, severity, fn in CHECKS:
        found = fn(text, lines) or []
        if found:
            results.setdefault(severity, {})[name] = found
    total = sum(len(v) for sev in results.values() for v in sev.values())
    for severity in ("fail", "warn", "info"):
        if severity not in results:
            continue
        print(f"\n## {severity.upper()}")
        for name, findings in results[severity].items():
            for line, msg in findings:
                loc = f"{path}:{line}" if line else path
                print(f"{loc} - {msg}")
    print(f"\n{total} finding(s)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design/interline-desktop.html")
