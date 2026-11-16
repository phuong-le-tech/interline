# Benchmarks

Measured on **Apple M4, 32 GB, macOS 27.2.1, synthetic TTS speech** (macOS `say`, not human voices). These are
upper bounds on quality and say nothing about accents, noise, crosstalk or telephone codecs. Real-call numbers come
from the M1 exit sitting (`docs/runbooks/M1-EXIT.md`).

Agent: `interline-capture-macos --bench` (whisper.cpp v1.9.5, Metal, Silero VAD v6.2.0, release build), one process
per file, model loaded before the clock starts (load time reported apart). Audio: six files of 27 to 33 s, 3 French
and 3 English, 48 kHz stereo, reference texts in `fixtures/speech/*.txt`. WER is computed by `scripts/bench/wer.py`
(lower-cased, punctuation removed, Levenshtein over words).

Reproduce:

```sh
scripts/fixtures/gen-speech.sh
python3 scripts/bench/fetch-models.py ~/.interline-models        # sha256 verified
scripts/bench/run-bench.sh ~/.interline-models /tmp/bench
python3 scripts/bench/summarize.py /tmp/bench
```

## Apple M4 32 GB (this Mac)

RTF = wall time / audio time (below 1 is faster than real time). Peak RSS is `ru_maxrss` of the agent process: it
does not include GPU memory that Metal maps, so it understates the footprint of the larger models.

| Model | RTF (mean) | Peak RSS (MB, max) | Load (s, mean) | WER FR (mean of 3) | WER EN (mean of 3) |
|---|---|---|---|---|---|
| tiny | 0.010 | 262 | 0.09 | 24.9 % | 2.0 % |
| base | 0.014 | 355 | 0.10 | 8.7 % | 3.3 % |
| small | 0.026 | 760 | 0.19 | 28.2 % | 3.1 % |
| large-v3-turbo-q5_0 | 0.053 | 785 | 0.21 | 0.4 % | 2.6 % |
| large-v3-turbo-q8_0 | 0.053 | 1090 | 0.32 | 0.4 % | 2.6 % |

Per file:

| Model | File | Audio (s) | Wall (s) | RTF | Words | Errors | WER |
|---|---|---|---|---|---|---|---|
| tiny | fr-1 | 32.71 | 0.4 | 0.012 | 90 | 15 | 16.7 % |
| tiny | fr-2 | 28.27 | 0.35 | 0.012 | 76 | 29 | 38.2 % |
| tiny | fr-3 | 27.01 | 0.3 | 0.011 | 71 | 14 | 19.7 % |
| tiny | en-1 | 28.82 | 0.28 | 0.01 | 91 | 2 | 2.2 % |
| tiny | en-2 | 31.25 | 0.29 | 0.009 | 76 | 0 | 0.0 % |
| tiny | en-3 | 29.49 | 0.26 | 0.009 | 77 | 3 | 3.9 % |
| base | fr-1 | 32.71 | 0.53 | 0.016 | 90 | 5 | 5.6 % |
| base | fr-2 | 28.27 | 0.39 | 0.014 | 76 | 6 | 7.9 % |
| base | fr-3 | 27.01 | 0.39 | 0.015 | 71 | 9 | 12.7 % |
| base | en-1 | 28.82 | 0.37 | 0.013 | 91 | 2 | 2.2 % |
| base | en-2 | 31.25 | 0.41 | 0.013 | 76 | 2 | 2.6 % |
| base | en-3 | 29.49 | 0.33 | 0.011 | 77 | 4 | 5.2 % |
| small | fr-1 | 32.71 | 0.63 | 0.019 | 90 | 70 | 77.8 % |
| small | fr-2 | 28.27 | 0.84 | 0.03 | 76 | 1 | 1.3 % |
| small | fr-3 | 27.01 | 0.84 | 0.031 | 71 | 4 | 5.6 % |
| small | en-1 | 28.82 | 0.78 | 0.027 | 91 | 0 | 0.0 % |
| small | en-2 | 31.25 | 0.82 | 0.026 | 76 | 5 | 6.6 % |
| small | en-3 | 29.49 | 0.7 | 0.024 | 77 | 2 | 2.6 % |
| large-v3-turbo-q5_0 | fr-1 | 32.71 | 2.31 | 0.071 | 90 | 1 | 1.1 % |
| large-v3-turbo-q5_0 | fr-2 | 28.27 | 1.3 | 0.046 | 76 | 0 | 0.0 % |
| large-v3-turbo-q5_0 | fr-3 | 27.01 | 1.3 | 0.048 | 71 | 0 | 0.0 % |
| large-v3-turbo-q5_0 | en-1 | 28.82 | 1.28 | 0.045 | 91 | 0 | 0.0 % |
| large-v3-turbo-q5_0 | en-2 | 31.25 | 2.15 | 0.069 | 76 | 4 | 5.3 % |
| large-v3-turbo-q5_0 | en-3 | 29.49 | 1.23 | 0.042 | 77 | 2 | 2.6 % |
| large-v3-turbo-q8_0 | fr-1 | 32.71 | 2.29 | 0.07 | 90 | 1 | 1.1 % |
| large-v3-turbo-q8_0 | fr-2 | 28.27 | 1.32 | 0.047 | 76 | 0 | 0.0 % |
| large-v3-turbo-q8_0 | fr-3 | 27.01 | 1.31 | 0.049 | 71 | 0 | 0.0 % |
| large-v3-turbo-q8_0 | en-1 | 28.82 | 1.27 | 0.044 | 91 | 0 | 0.0 % |
| large-v3-turbo-q8_0 | en-2 | 31.25 | 2.09 | 0.067 | 76 | 4 | 5.3 % |
| large-v3-turbo-q8_0 | en-3 | 29.49 | 1.24 | 0.042 | 77 | 2 | 2.6 % |

Findings:

- Every model is far faster than real time on this Mac (RTF 0.01 to 0.07), so speed is not the constraint here.
- `large-v3-turbo` (q5_0 and q8_0) reached 0.4 % WER in French and 2.6 % in English; the q5_0 file gives the same text
  as q8_0 on these clips at 574 MB instead of 874 MB.
- `small` is good in English (3.1 %) but **lost the middle of one French clip** (`fr-1`, WER 78 %): the clip has no
  pause, so it was forced into a single 30 s chunk and whisper skipped about 60 words. `tiny` is poor in French
  (25 %). Real speech has pauses, so chunks close earlier, but this is the risk to re-check on the real call before
  `small` stays the default below 16 GB RAM.
- Synthetic voices are clean and unhurried: expect worse WER on real calls.
