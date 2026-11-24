/** Pure helpers behind the live recording view, kept out of the components so they stay testable. */

export type TranscriptRow = {
  key: string;
  speaker: string;
  text: string;
  final: boolean;
  rev: number;
};

export type BarState =
  | 'idle'
  | 'starting'
  | 'recording'
  | 'capture restarting'
  | 'stopping'
  | 'done'
  | 'recovered'
  | 'error'
  | 'state unknown';

export type PermissionKind = 'microphone' | 'system_audio' | 'calendar';

export const SYSTEM_AUDIO_SETTINGS_URI =
  'x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture';
export const CALENDAR_SETTINGS_URI =
  'x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars';
export const MICROPHONE_SETTINGS_URI =
  'x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone';
/** The only URL scheme the "Open System Settings" action may open. */
export const SETTINGS_SCHEME = 'x-apple.systempreferences:';

const SILENCE_FLOOR_DB = -60;
const PERCENT = 100;
const BAR_STATES: Record<string, BarState> = {
  idle: 'idle',
  done: 'done',
  recovered: 'recovered',
  starting: 'starting',
  recording: 'recording',
  degraded: 'capture restarting',
  processing: 'stopping',
  failed: 'error',
};

export function barState(coreState: string): BarState {
  return BAR_STATES[coreState] ?? 'state unknown';
}

/** Map an RMS amplitude (0..1) to a 0..100 meter value on a -60..0 dBFS scale. */
export function meterValue(rms: number): number {
  if (!Number.isFinite(rms) || rms <= 0) return 0;
  const decibels = 20 * Math.log10(rms);
  return Math.round(Math.min(1, Math.max(0, (decibels - SILENCE_FLOOR_DB) / -SILENCE_FLOOR_DB)) * PERCENT);
}

type SegmentEvent = { stream: string; seg: string; speaker: string; text: string; rev?: number };

function rowKey(event: SegmentEvent): string {
  return `${event.stream}:${event.seg}`;
}

/** A partial revises its row in place; a final settles that same row and is never overwritten. */
export function applyPartial(rows: TranscriptRow[], event: SegmentEvent): TranscriptRow[] {
  const key = rowKey(event);
  const rev = event.rev ?? 0;
  const existing = rows.find((row) => row.key === key);
  if (!existing) return [...rows, { key, speaker: event.speaker, text: event.text, final: false, rev }];
  if (existing.final || rev < existing.rev) return rows;
  return rows.map((row) => (row === existing ? { ...row, text: event.text, rev } : row));
}

export function applyFinal(rows: TranscriptRow[], event: SegmentEvent): TranscriptRow[] {
  const key = rowKey(event);
  const settled: TranscriptRow = { key, speaker: event.speaker, text: event.text, final: true, rev: Number.MAX_SAFE_INTEGER };
  return rows.some((row) => row.key === key) ? rows.map((row) => (row.key === key ? settled : row)) : [...rows, settled];
}

/** What the live panel holds: the visible rows, plus the keys the core already suppressed. */
export type LiveTranscript = { rows: TranscriptRow[]; suppressed: string[] };

export const EMPTY_TRANSCRIPT: LiveTranscript = { rows: [], suppressed: [] };

/**
 * Apply one transcript SSE event. `stt.partial` and `stt.final` settle their row unless the key was already
 * suppressed; `transcript.suppressed` removes the row of that key and remembers it, so a later partial or final on
 * the same key cannot bring it back. Any other event name leaves the state untouched.
 */
export function applyLiveEvent(state: LiveTranscript, name: string, data: unknown): LiveTranscript {
  if (name === 'transcript.suppressed') {
    const event = data as { stream: string; seg: string };
    const key = `${event.stream}:${event.seg}`;
    return {
      rows: state.rows.filter((row) => row.key !== key),
      suppressed: state.suppressed.includes(key) ? state.suppressed : [...state.suppressed, key],
    };
  }

  if (name !== 'stt.partial' && name !== 'stt.final') return state;

  const event = data as SegmentEvent;
  const key = rowKey(event);
  if (state.suppressed.includes(key)) return state;

  const rows = name === 'stt.final'
    ? applyFinal(state.rows, event)
    : applyPartial(state.rows, event);
  return { ...state, rows };
}

export function isSettingsUri(uri: string): boolean {
  return uri.startsWith(SETTINGS_SCHEME);
}

export function formatBytes(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${unit === 0 ? value : value.toFixed(1)} ${units[unit]}`;
}
