#!/usr/bin/env python3
"""Word error rate between a reference text and a hypothesis (stdlib only).

Usage: wer.py REFERENCE.txt HYPOTHESIS.(txt|json)
A .json hypothesis is the agent's --bench report; its "text" field is compared.
Both texts are lower-cased, stripped of punctuation and compared word by word with a Levenshtein distance.
Prints one JSON line: {"words": n, "errors": e, "wer": e/n}.
"""
import json
import pathlib
import re
import sys
import tempfile
import unicodedata

WORD = re.compile(r"[^\W_]+(?:['’][^\W_]+)*", re.UNICODE)


def _allowed_roots() -> list[pathlib.Path]:
    roots = [pathlib.Path.cwd(), pathlib.Path.home()]
    tmp = pathlib.Path("/tmp").resolve()
    if tmp not in roots:
        roots.append(tmp)
    try:
        tmpdir = pathlib.Path(tempfile.gettempdir()).resolve()
        if tmpdir not in roots:
            roots.append(tmpdir)
    except (OSError, RuntimeError):
        pass
    return roots


def _existing_file(path: str) -> pathlib.Path:
    if "\x00" in path:
        raise SystemExit(f"invalid path: {path}")
    p = pathlib.Path(path).resolve()
    if not p.is_file():
        raise SystemExit(f"not a file: {path}")
    if not any(p == root or p.is_relative_to(root) for root in _allowed_roots()):
        raise SystemExit(f"path is outside allowed directories: {path}")
    return p


def words(text: str) -> list[str]:
    folded = unicodedata.normalize("NFC", text).lower().replace("’", "'")
    return WORD.findall(folded)


def distance(reference: list[str], hypothesis: list[str]) -> int:
    previous = list(range(len(hypothesis) + 1))
    for i, ref_word in enumerate(reference, start=1):
        current = [i]
        for j, hyp_word in enumerate(hypothesis, start=1):
            cost = 0 if ref_word == hyp_word else 1
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost))
        previous = current
    return previous[-1]


def wer(reference_text: str, hypothesis_text: str) -> dict:
    reference = words(reference_text)
    errors = distance(reference, words(hypothesis_text))
    return {"words": len(reference), "errors": errors, "wer": round(errors / len(reference), 4) if reference else 0.0}


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    ref_path = _existing_file(argv[1])
    hyp_path = _existing_file(argv[2])
    with ref_path.open(encoding="utf-8") as ref, hyp_path.open(encoding="utf-8") as hyp:
        hypothesis = json.load(hyp)["text"] if str(hyp_path).endswith(".json") else hyp.read()
        print(json.dumps(wer(ref.read(), hypothesis)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
