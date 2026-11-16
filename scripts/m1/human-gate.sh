#!/bin/sh
# The M1 human sitting (decision 12): runs spikes (a) to (e) of docs/runbooks/M1-EXIT.md with Phuong at the
# keyboard and writes ~/Library/Application Support/app.interline.dev/m1-gate/results.json.
# Everything here needs a person: system permission dialogs and listening to speakers cannot be automated.
# The script checks the database itself before it asks a question whose answer depends on a transcript, so a
# sitting cannot be spent on a setup that produced nothing (the first sitting had no speech model installed).
# Usage: scripts/m1/human-gate.sh [--skip-build] [--rerun]
#   --skip-build  reuse the Interline.app that is already built
#   --rerun       the short sitting: spikes (a) (b) (c) (e) only; spike (d) keeps its answers from the first sitting
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
out_dir="$HOME/Library/Application Support/app.interline.dev/m1-gate"
results="$out_dir/results.json"
app="$root/apps/desktop/src-tauri/target/debug/bundle/macos/Interline.app"
data_dir="$HOME/Library/Application Support/app.interline.desktop"
models_dir="$data_dir/models"
log_dir="$HOME/Library/Logs/app.interline.desktop"
build_mark="$root/agents/macos-capture/Sources/CaptureAgent/Options.swift"
bundle_ids="app.interline.desktop app.interline.capture"
# tccutil exits 64 (usage) for an identifier it has never seen a grant for.
tccutil_usage_exit=64
skip_build=no
rerun=no
for arg in "$@"; do
  case "$arg" in
    --skip-build) skip_build=yes ;;
    --rerun) rerun=yes ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

mkdir -p "$out_dir"
: > "$out_dir/entries.tmp"
if [ "$rerun" = yes ]; then
  [ -f "$results" ] || { echo "--rerun needs the results.json of the first sitting" >&2; exit 1; }
  cp "$results" "$out_dir/results.first-sitting.json"
  # Spike (d) was answered honestly the first time; the other answers are replaced.
  grep '^  "d_' "$results" >> "$out_dir/entries.tmp" || true
fi

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
now() { python3 -c 'import time; print(time.time())'; }
elapsed() { python3 -c "print(round($2 - $1, 1))"; }
finish() {
  [ ! -f "$build_mark.orig" ] || mv "$build_mark.orig" "$build_mark"
  { printf '{\n'; cat "$out_dir/entries.tmp"; printf '  "finished": "%s"\n}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"; } > "$results"
  echo "results written to $results"
}
trap finish EXIT

stop_app() { pkill -x interline 2>/dev/null || true; pkill -x interline-capture-macos 2>/dev/null || true; sleep 1; }
reset_grants() {
  for service in Microphone AudioCapture; do
    for id in $bundle_ids; do
      status=0; tccutil reset "$service" "$id" >/dev/null 2>&1 || status=$?
      if [ "$status" = "$tccutil_usage_exit" ]; then
        record "tccutil_reset_${service}_${id}" "exit $status: bundle id not registered with TCC (never granted or prompted), not a failing reset"
      else
        record "tccutil_reset_${service}_${id}" "exit $status"
      fi
    done
  done
}
launch() { # launch [AGENT_ARGS]
  stop_app
  if [ -n "${1:-}" ]; then open -n --env "INTERLINE_AGENT_ARGS=$1" "$app"; else open -n "$app"; fi
}

# --- database checks -------------------------------------------------------------------------------------------
database() { ls -t "$data_dir"/*.sqlite 2>/dev/null | head -1 || true; }
latest_meeting_sql="(SELECT id FROM meetings ORDER BY started_at DESC LIMIT 1)"
rows() { # rows SOURCE [visible] -> number of segments of the latest meeting for mic or system
  db=$(database)
  [ -n "$db" ] || { echo 0; return 0; }
  extra=""; [ "${2:-}" = visible ] && extra=" AND t.suppressed = 0"
  sqlite3 -readonly "$db" "SELECT count(*) FROM transcript_segments t JOIN speakers s ON s.id = t.speaker_id WHERE t.meeting_id = $latest_meeting_sql AND s.source = '$1'$extra;" 2>/dev/null || echo 0
}
last_transcript() {
  db=$(database)
  [ -n "$db" ] || { echo "(no database found in $data_dir)"; return 0; }
  sqlite3 -readonly "$db" "SELECT s.label, t.suppressed, substr(t.text,1,70) FROM transcript_segments t JOIN speakers s ON s.id = t.speaker_id WHERE t.meeting_id = $latest_meeting_sql ORDER BY t.start_ms;" 2>&1 | head -30 || true
}
# require_rows LABEL SOURCE... : do not go on until each source has transcript rows; the person can retry or give up.
require_rows() {
  label=$1; shift
  while :; do
    missing=""
    for source in "$@"; do
      count=$(rows "$source")
      record "${label}_rows_${source}" "$count"
      [ "$count" -gt 0 ] || missing="$missing $source"
    done
    [ -z "$missing" ] && return 0
    echo "No transcript rows for:$missing. The speech pipeline or the capture produced nothing, so the next question"
    echo "could not be answered honestly. Check the Models screen shows 'small' installed and read the logs in"
    echo "$log_dir."
    printf 'Retry the recording (r) or abort the sitting (q)? '; read -r choice
    case "$choice" in
      r|R) launch; wait_enter "Record again (speak and play audio, about 20 s), Stop, then press" ;;
      *) record aborted "no transcript rows for:$missing in $label"; exit 1 ;;
    esac
  done
}

record host "$(sw_vers -productName) $(sw_vers -productVersion) $(uname -m)"

if [ "$skip_build" = no ]; then
  say_step "0. Signing identity and build"
  "$root/scripts/macos/signing-identity.sh"
  INTERLINE_SIGN=identity "$root/scripts/macos/build-dev-app.sh"
fi
[ -d "$app" ] || { echo "build the app first (scripts/macos/build-dev-app.sh)"; exit 1; }

say_step "0b. Speech models (one download, checked by sha256; the app refuses to record without them)"
python3 "$root/scripts/bench/fetch-models.py" "$models_dir" small silero-v6.2.0
ls -l "$models_dir"
record models_installed "$(ls "$models_dir" | tr '\n' ' ')"

say_step "(a)+(b) Attribution and process tap"
echo "Resetting previous grants so the system asks again."
reset_grants
launch
cat <<'EOF'
Interline opens. Click Record. Within 5 seconds macOS must show permission dialogs:
  1. "Interline would like to access the microphone"  -> Allow
  2. a system audio recording prompt                   -> Allow
Read the app name written in each dialog before you click.
Then say a full sentence aloud AND play speech from another app (a YouTube video, or run:
  say "testing the other side of the call, one two three")
Keep both going for about 20 seconds, then click Stop in Interline.
EOF
wait_enter "Done and stopped? Press"
note a_mic_dialog_names "Name shown in the microphone dialog (exactly), or 'none':"
note a_system_dialog_names "Name shown in the system audio dialog (exactly), or 'none':"
ask a_mic_prompt_appeared "Did the microphone dialog appear?"
ask a_system_prompt_appeared "Did a system audio dialog appear?"
echo "System Settings > Privacy & Security > Microphone and > Screen & System Audio Recording > System Audio Recording Only."
note a_settings_entry_names "Which app names are listed there for Interline (exactly, comma separated), or 'none':"
ask b_me_meter_moved "Did the Me meter move within 5 s of speaking?"
ask b_them_meter_moved "Did the Them meter move within 5 s of playing audio?"
require_rows b mic system
echo "Segments of that meeting (speaker, suppressed, text):"
last_transcript | tee "$out_dir/b.txt"
ask b_them_text_appeared "Does the Them text above match what the other app played?"
ask b_me_text_appeared "Does the Me text above match what you said?"

say_step "(c) Echo cancellation: voice processing off, then on"
for mode in off on; do
  launch "--voice-processing=$mode"
  echo "Voice processing: $mode. Use the laptop SPEAKERS at a normal volume (no headphones) and keep your voice silent."
  echo "Click Record, then in another Terminal tab run:  say 'the quick brown fox jumps over the lazy dog'  (twice), then Stop."
  wait_enter "Done and stopped? Press"
  # The system stream must have heard the sentence, or the comparison between the two modes means nothing.
  require_rows "c_${mode}" system
  record "c_mic_rows_visible_${mode}" "$(rows mic visible)"
  echo "Segments of that meeting (speaker, suppressed, text):"
  last_transcript | tee "$out_dir/c_${mode}.txt"
  ask "c_mic_echo_visible_${mode}" "Does a Me row (suppressed 0) contain the sentence?"
done

if [ "$rerun" = no ]; then
  say_step "(d) Stable identity versus ad-hoc signature after a code change"
  if [ -f "$build_mark" ]; then cp "$build_mark" "$build_mark.orig"; printf '\n// m1 gate build mark %s\n' "$(date +%s)" >> "$build_mark"; fi
  INTERLINE_SIGN=identity "$root/scripts/macos/build-dev-app.sh" >/dev/null
  launch
  echo "Rebuilt with a code change, SAME identity. Grants were NOT reset. Click Record and play audio, then Stop."
  wait_enter "Done? Press"
  ask d_identity_mic_reprompted "Did macOS ask for the microphone again?"
  ask d_identity_sys_reprompted "Did macOS ask for system audio again?"
  ask d_identity_stream_silent "Was the Them meter flat although audio was playing?"
  printf '\n// m1 gate build mark %s\n' "$(date +%s)" >> "$build_mark"
  INTERLINE_SIGN=adhoc "$root/scripts/macos/build-dev-app.sh" >/dev/null
  launch
  echo "Rebuilt AD-HOC signed. Click Record and play audio, then Stop."
  wait_enter "Done? Press"
  ask d_adhoc_mic_reprompted "Did macOS ask for the microphone again?"
  ask d_adhoc_sys_reprompted "Did macOS ask for system audio again?"
  ask d_adhoc_stream_silent "Was the Them meter flat although audio was playing?"
  mv "$build_mark.orig" "$build_mark"
  INTERLINE_SIGN=identity "$root/scripts/macos/build-dev-app.sh" >/dev/null
fi

say_step "(e) Denied system audio: time to the banner"
reset_grants
launch
echo "Click Record. Allow the microphone. DENY the system audio dialog. Keep audio playing in another app."
echo "The clock starts when you click Deny (the detector needs 5 s of silence after that) and stops when the red"
echo "banner \"I can't hear the other participants\" appears. Two Enters: one at the Deny click, one at the banner."
wait_enter "Start (press Enter as you click Deny)"
started=$(now)
wait_enter "Banner appeared (press Enter)"
record e_seconds_to_banner "$(elapsed "$started" "$(now)")"
ask e_banner_appeared "Did the banner appear on its own, without you doing anything else?"
ask e_banner_has_settings_link "Does the banner show an 'Open System Settings' link?"
echo "Click the link."
ask e_link_opens_pane "Did it open System Settings on Screen & System Audio Recording?"
stop_app
echo "Done. Thank you."
