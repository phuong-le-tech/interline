#!/usr/bin/env python3
"""Turns the output of run-bench.sh into the markdown tables of docs/BENCHMARKS.md (stdlib only).

Usage: summarize.py BENCH_OUT_DIR
"""
import json
import pathlib
import statistics
import sys

MODELS = ["tiny", "base", "small", "large-v3-turbo-q5_0", "large-v3-turbo-q8_0"]


def load(directory: pathlib.Path, model: str, language: str) -> list[tuple[dict, dict]]:
    rows = []
    for report in sorted(directory.glob(f"{model}-{language}-*.json")):
        if report.name.endswith(".wer.json"):
            continue
        data = json.loads(report.read_text())
        data["file"] = report.stem[len(model) + 1:]
        rows.append((data, json.loads(report.with_suffix(".wer.json").read_text())))
    return rows


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    directory = pathlib.Path(argv[1])
    print("| Model | RTF (mean) | Peak RSS (MB, max) | Load (s, mean) | WER FR (mean of 3) | WER EN (mean of 3) |")
    print("|---|---|---|---|---|---|")
    for model in MODELS:
        fr, en = load(directory, model, "fr"), load(directory, model, "en")
        runs = fr + en
        if not runs:
            continue
        rtf = statistics.mean(r["rtf"] for r, _ in runs)
        rss = max(r["peak_rss_mb"] for r, _ in runs)
        load_s = statistics.mean(r["load_s"] for r, _ in runs)
        wer_fr = statistics.mean(w["wer"] for _, w in fr)
        wer_en = statistics.mean(w["wer"] for _, w in en)
        print(f"| {model} | {rtf:.3f} | {rss} | {load_s:.2f} | {wer_fr * 100:.1f} % | {wer_en * 100:.1f} % |")
    print()
    print("| Model | File | Audio (s) | Wall (s) | RTF | Words | Errors | WER |")
    print("|---|---|---|---|---|---|---|---|")
    for model in MODELS:
        for language in ("fr", "en"):
            for report, wer in load(directory, model, language):
                print(f"| {model} | {report['file']} | {report['audio_s']} | {report['wall_s']} | {report['rtf']} "
                      f"| {wer['words']} | {wer['errors']} | {wer['wer'] * 100:.1f} % |")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
