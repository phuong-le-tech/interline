# Agent Protocol v1 red-test corpus

`golden/m0.ndjson` records the 15 M0 message types, including the decoded `audio.chunk` manifest. `golden/audio.chunk.bin` is the framed binary example, with unsigned sequence 4294967295 and signed s16le sample extremes. The hex file is a readable representation of the same bytes.

`schema.json` is deliberately an empty schema skeleton. The rejection assertion in `GoldenTest` fails until the implementation supplies the real M0 contract. Passing validation against the empty schema is not completion evidence.

The corpus is synthetic and has no audio recording or credential. Existing examples are append-only under ADR-002.
