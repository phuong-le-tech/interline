#!/bin/sh
# The single M1 human sitting (decision 12): runs spikes (a) to (e) of docs/runbooks/M1-EXIT.md with Phuong at the
# keyboard and writes ~/Library/Application Support/app.interline.dev/m1-gate/results.json.
# Everything here needs a person: system permission dialogs and listening to speakers cannot be automated.
# Usage: scripts/m1/human-gate.sh [--skip-build]
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
out_dir="$HOME/Library/Application Support/app.interline.dev/m1-gate"
results="$out_dir/results.json"
app="$root/apps/desktop/src-tauri/target/debug/bundle/macos/Interline.app"
data_dir="$HOME/Library/Application Support/app.interline.desktop"
build_mark="$root/agents/macos-capture/Sources/CaptureAgent/Options.swift"
bundle_ids="app.interline.desktop app.interline.capture"

mkdir -p "$out_dir"
: > "$out_dir/entries.tmp"

record() { # record KEY VALUE (value is stored as a JSON string)
  escaped=$(printf '%s' "$2" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n' ' ')
  printf '  "%s": "%s",\n' "$1" "$escaped" >> "$out_dir/entries.tmp"
}
say_step() { printf '\n== %s\n' "$*"; }
wait_enter() { printf '%s [Enter] ' "$*"; read -r _; }
ask() { # ask KEY QUESTION -> records yes/no
  while :; do
    printf '%s [y/n] ' "$2"; read -r answer
    case "$answer" in y|Y|yes) record "$1" yes; return 0 ;; n|N|no) record "$1" no; return 0 ;; esac
  done
}
note() { printf '%s ' "$2"; read -r text; record "$1" "$text"; }
finish() {
  [ -f "$build_mark.orig" ] && mv "$build_mark.orig" "$build_mark"
  { printf '{\n'; cat "$out_dir/entries.tmp"; printf '  "finished": "%s"\n}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$results"
  echo "results written to $results"
}
trap finish EXIT

stop_app() { pkill -x interline 2>/dev/null || true; pkill -x interline-capture-macos 2>/dev/null || true; sleep 1; }
reset_grants() {
  for service in Microphone AudioCapture; do
    for id in $bundle_ids; do
      status=0; tccutil reset "$service" "$id" >/dev/null 2>&1 || status=$?
      record "tccutil_reset_${service}_${id}" "exit $status"
    done
  done
}
launch() { # launch [AGENT_ARGS]
  stop_app
  if [ -n "${1:-}" ]; then open -n --env "INTERLINE_AGENT_ARGS=$1" "$app"; else open -n "$app"; fi
}
last_transcript() {
  db=$(ls -t "$data_dir"/*.sqlite "$data_dir"/*.db 2>/dev/null | head -1 || true)
  [ -n "$db" ] || { echo "(no database found in $data_dir)"; return 0; }
  sqlite3 -readonly "$db" "SELECT s.label, t.suppressed, substr(t.text,1,70) FROM transcript_segments t JOIN speakers s ON s.id = t.speaker_id WHERE t.meeting_id = (SELECT id FROM meetings ORDER BY started_at DESC LIMIT 1) ORDER BY t.start_ms;" 2>&1 | head -30 || true
}

record host "$(sw_vers -productName) $(sw_vers -productVersion) $(uname -m)"

if [ "${1:-}" != "--skip-build" ]; then
  say_step "0. Signing identity and build"
  "$root/scripts/macos/signing-identity.sh"
  "$root/scripts/macos/build-dev-app.sh"
fi
[ -d "$app" ] || { echo "build the app first (scripts/macos/build-dev-app.sh)"; exit 1; }

say_step "(a)+(b) Attribution and process tap"
echo "Resetting previous grants so the system asks again."
reset_grants
launch
cat <<'EOF'
Interline opens. Click Record. Within 5 seconds macOS must show permission dialogs:
  1. "Interline would like to access the microphone"  -> Allow
  2. a system audio recording prompt                   -> Allow
Then say a sentence aloud AND play audio from another app (a YouTube video, or run: say "testing the other side").
Success: both the Me and Them meters move within 5 seconds, and text appears in the transcript.
EOF
wait_enter "Do it now, then press Enter"
note a_mic_dialog_names "Name shown in the microphone dialog (exactly):"
note a_system_dialog_names "Name shown in the system audio dialog (exactly), or 'none':"
ask a_mic_prompt_appeared "Did the microphone dialog appear?"
ask a_system_prompt_appeared "Did a system audio dialog appear?"
ask b_me_meter_moved "Did the Me meter move within 5 s of speaking?"
ask b_them_meter_moved "Did the Them meter move within 5 s of playing audio?"
ask b_them_text_appeared "Did Them text appear in the transcript?"
echo "System Settings > Privacy & Security > Microphone and > Screen & System Audio Recording > System Audio Recording Only."
note a_settings_entry_names "Which app names are listed there for Interline (exactly, comma separated)?"
echo "Click Stop in Interline."
wait_enter "Stopped?"

say_step "(c) Echo cancellation: voice processing off, then on"
for mode in off on; do
  launch "--voice-processing=$mode"
  echo "Voice processing: $mode. Use the laptop SPEAKERS (no headphones), keep your voice silent."
  echo "Click Record, then run in another Terminal tab:  say 'the quick brown fox jumps over the lazy dog'  (twice), then Stop."
  wait_enter "Done?"
  echo "Segments of the last meeting (speaker, suppressed, text):"
  last_transcript | tee "$out_dir/c_${mode}.txt"
  ask "c_mic_echo_visible_${mode}" "Did the sentence show up as a visible (not suppressed) Me row?"
done

say_step "(d) Stable identity versus ad-hoc signature after a code change"
if [ -f "$build_mark" ]; then cp "$build_mark" "$build_mark.orig"; printf '\n// m1 gate build mark %s\n' "$(date +%s)" >> "$build_mark"; fi
INTERLINE_SIGN=identity "$root/scripts/macos/build-dev-app.sh" >/dev/null
launch
echo "Rebuilt with a code change, SAME identity. Grants were NOT reset. Click Record and play audio, then Stop."
wait_enter "Done?"
ask d_identity_microphone_reprompted "Did macOS ask for the microphone again?"
ask d_identity_system_audio_reprompted "Did macOS ask for system audio again?"
ask d_identity_stream_silent "Was the Them meter flat although audio was playing?"
printf '\n// m1 gate build mark %s\n' "$(date +%s)" >> "$build_mark"
INTERLINE_SIGN=adhoc "$root/scripts/macos/build-dev-app.sh" >/dev/null
launch
echo "Rebuilt AD-HOC signed. Click Record and play audio, then Stop."
wait_enter "Done?"
ask d_adhoc_microphone_reprompted "Did macOS ask for the microphone again?"
ask d_adhoc_system_audio_reprompted "Did macOS ask for system audio again?"
ask d_adhoc_stream_silent "Was the Them meter flat although audio was playing?"
mv "$build_mark.orig" "$build_mark"

say_step "(e) Denied system audio: time to the banner"
INTERLINE_SIGN=identity "$root/scripts/macos/build-dev-app.sh" >/dev/null
reset_grants
launch
echo "Click Record. Allow the microphone. DENY the system audio dialog. Play audio from another app."
echo "Press Enter at the moment you click Record, and Enter again when the red banner 'I can't hear the other participants' appears."
wait_enter "Start (press Enter as you click Record)"
started=$(date +%s)
wait_enter "Banner appeared (press Enter)"
record e_seconds_to_banner "$(( $(date +%s) - started ))"
ask e_banner_has_settings_link "Does the banner have an 'Open System Settings' link that opens the right pane?"
stop_app
echo "Done. Thank you."
