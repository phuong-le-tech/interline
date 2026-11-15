# M1 acceptance evidence checks

These scripts are tests, not application logic. Missing artifacts fail explicitly.
No script downloads a model or creates substitute benchmark/latency measurements.

## AC8_catalog

```sh
scripts/m1/catalog-check.sh
# Or an explicit catalog source:
scripts/m1/catalog-check.sh path/to/catalog.json
```

Run once online after implementing the catalog. The default source is
`services/core/app/src/main/resources/models/catalog.json`. Expected catalog:
a JSON array (or an object with a `models` array), each entry with `id`, `url`,
`sha256`, `sizeBytes`. These field names match the new Java/browser fixture
catalogs. All six binding IDs are mandatory, without duplicates. URLs must pin
full 40-character HF commits. The checker sends HEAD only, checks the original
redirect response's `x-linked-etag` and `x-linked-size`, and records full values.
Missing headers, HTTP errors and timeouts are failures, not offline proof.

Local integration fixtures use `interline.models.catalog=<file>` as a proposed
configuration seam. That file is loaded locally; its model URLs point at the
in-process HTTP fixture. The production packaged catalog still requires HTTPS
commit-pinned HF URLs. Do not relax the production catalog verifier to admit the
local test origins.

## AC11_latency

The implementation stage must first execute a real ten-minute session at speed
1 using `small` and the Swift agent. Collect actual final-event receipts, logs
and the authenticated `GET /api/v1/meetings/{id}/stats` response. Then run:

```sh
scripts/m1/latency.sh path/to/evidence-directory
```

Required evidence files:

- `run.json`: actual `model`, `speed`, `audio_s`, `wall_s`, `agent` fields.
- `finals.ndjson`: one row per actual final with `stream`, `receipt_wall_ms`,
  `t0_wall_ms`, `end_sample`, `stt_latency_ms`. The receipt must be captured when
  the final arrives, not retrospectively derived from the reported latency.
- `stats.json`: the API response, proposed shape
  `{ "mic": { "stt_latency_ms": { "median": ..., "p95": ... } }, "system": ... }`.
- `core.log`: actual lines `stt_latency_ms=<number> stream=<mic|system>`.

The checker recomputes the sample-to-wall-time formula, median and empirical
p95 bracket for both streams. It does not impose an unspecified interpolation
convention. This is an evidence verifier, not the ten-minute measurement runner;
no performance number has been obtained by creating these tests. Adapt the
serialization adapter if the implementation defines a different API shape,
without changing the underlying arithmetic assertions.

## AC16_hygiene

```sh
scripts/m1/repo-check.sh
```

Checks `git ls-files` for generated audio, models, instruction files and common
key formats. Compares every M0 test/helper and M0 golden against `7ecff48`.
Compares unchanged `core`/`ui` CI job blocks byte-for-byte, requires the native
`macos-15` job and its build/selftest/fixtures/conformance commands, then runs
installed `actionlint`. Missing tooling is failure. The native job is deliberately
not added by the test-writer: its absence is the current AC16 red assertion.

AC13/AC14/AC15/AC17 remain implementation evidence or human gates from the plan,
not fabricated test-writer results. No audio, models, credentials or signing
material are committed.
