#!/bin/sh
# Benchmarks every installed catalog model on the synthetic speech fixtures: real-time factor, peak RSS, WER.
# Usage: scripts/bench/run-bench.sh MODELS_DIR OUT_DIR
# Needs: the release agent build, fixtures from scripts/fixtures/gen-speech.sh, models from fetch-models.py.
set -eu

models=${1:?usage: run-bench.sh MODELS_DIR OUT_DIR}
out=${2:?usage: run-bench.sh MODELS_DIR OUT_DIR}
root=$(cd "$(dirname "$0")/../.." && pwd)
agent="$root/agents/macos-capture/.build/release/interline-capture-macos"
vad="$models/ggml-silero-v6.2.0.bin"
fixtures="$root/fixtures/speech"

[ -x "$agent" ] || { echo "build the agent first: cd agents/macos-capture && swift build -c release" >&2; exit 1; }
[ -f "$vad" ] || { echo "missing $vad (fetch-models.py silero-v6.2.0)" >&2; exit 1; }
mkdir -p "$out"

for model in tiny base small large-v3-turbo-q5_0 large-v3-turbo-q8_0; do
  file="$models/ggml-$model.bin"
  [ -f "$file" ] || { echo "skip $model: not installed" >&2; continue; }
  for wav in "$fixtures"/*.wav; do
    name=$(basename "$wav" .wav)
    language=${name%%-*}
    base="$out/$model-$name"
    "$agent" --bench --model="$file" --vad-model="$vad" --input="$wav" --language="$language" \
      > "$base.json" 2> "$base.log"
    python3 "$root/scripts/bench/wer.py" "$fixtures/$name.txt" "$base.json" > "$base.wer.json"
    echo "$model $name $(cat "$base.wer.json") $(sed 's/"text":"[^"]*",\{0,1\}//' "$base.json")"
  done
done
