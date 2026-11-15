#!/bin/sh
# Generates the synthetic speech fixtures used by the benchmarks and the latency run.
# Needs macOS (`say`, `afconvert`). Audio is never committed: fixtures/**/*.wav is ignored.
# Output: fixtures/speech/<name>.wav, 48 kHz stereo 16 bit, the same speech on both channels.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
dir=$(cd "$here/../../fixtures/speech" && pwd)
rate=165

voice_for() {
  case "$1" in
    fr-1) echo "Thomas" ;;
    fr-2) echo "Jacques" ;;
    fr-3) echo "Flo (French (France))" ;;
    en-1) echo "Samantha" ;;
    en-2) echo "Reed (English (US))" ;;
    en-3) echo "Flo (English (US))" ;;
    *) echo "unknown fixture $1" >&2; return 1 ;;
  esac
}

work=$(mktemp -d "${TMPDIR:-/tmp}/interline-speech.XXXXXX")
trap 'rm -rf "$work"' EXIT

for text in "$dir"/*.txt; do
  name=$(basename "$text" .txt)
  voice=$(voice_for "$name")
  say -v "$voice" -r "$rate" -o "$work/$name.aiff" -f "$text"
  # -c 2 duplicates the mono voice into both channels.
  afconvert -f WAVE -d LEI16@48000 -c 2 "$work/$name.aiff" "$dir/$name.wav"
  echo "wrote $dir/$name.wav ($voice)"
done
