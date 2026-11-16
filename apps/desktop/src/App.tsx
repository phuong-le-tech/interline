import { useCallback, useEffect, useMemo, useState } from 'react';
import { CoreClient, waitForEndpoint, type CoreEvent, type Meeting, type ModelView } from './coreClient';
import {
  MICROPHONE_SETTINGS_URI,
  SYSTEM_AUDIO_SETTINGS_URI,
  applyFinal,
  applyPartial,
  barState,
  type PermissionKind,
  type TranscriptRow,
} from './liveState';
import { Meters, type Levels } from './Meters';
import { Models } from './Models';
import { PermissionBanner } from './PermissionBanner';

const SLOW_START_MS = 1500;
const ACTIVE_STATES = new Set(['starting', 'recording', 'degraded', 'processing']);
const PERMISSION_MISSING = 'permission_missing';
const MODEL_MISSING = 'model_missing';
const SILENT_LEVELS: Levels = { mic: 0, system: 0 };

type Banners = Partial<Record<PermissionKind, string>>;
type View = 'live' | 'models';

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

export function App() {
  const [client, setClient] = useState<CoreClient>();
  const [slowStart, setSlowStart] = useState(false);
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [rows, setRows] = useState<TranscriptRow[]>([]);
  const [models, setModels] = useState<ModelView[]>([]);
  const [levels, setLevels] = useState<Levels>(SILENT_LEVELS);
  const [banners, setBanners] = useState<Banners>({});
  const [state, setState] = useState('idle');
  const [error, setError] = useState<string>();
  const [view, setView] = useState<View>('live');

  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => setSlowStart(true), SLOW_START_MS);
    void waitForEndpoint(controller.signal).then((endpoint) => {
      clearTimeout(timer);
      if (endpoint) setClient(new CoreClient(endpoint));
    });
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, []);

  const refreshMeetings = useCallback(async (core: CoreClient) => {
    setMeetings(await core.meetings());
  }, []);

  useEffect(() => {
    if (!client) return;
    const controller = new AbortController();
    const onEvent = (event: CoreEvent) => {
      const data = event.data;
      switch (event.name) {
        case 'recording.state':
          setState(text(data.state));
          if (data.state !== 'recording' && data.state !== 'degraded') setLevels(SILENT_LEVELS);
          void refreshMeetings(client);
          break;
        case 'level': {
          const rms = Number(data.rms);
          setLevels((current) => (data.stream === 'system' ? { ...current, system: rms } : { ...current, mic: rms }));
          break;
        }
        case 'stt.partial':
          setRows((current) => applyPartial(current, data as never));
          break;
        case 'stt.final':
          setRows((current) => applyFinal(current, data as never));
          break;
        case 'permission.denied': {
          const kind: PermissionKind = data.permission === 'microphone' ? 'microphone' : 'system_audio';
          const fallback = kind === 'microphone' ? MICROPHONE_SETTINGS_URI : SYSTEM_AUDIO_SETTINGS_URI;
          setBanners((current) => ({ ...current, [kind]: text(data.settings_uri) || fallback }));
          break;
        }
        case 'recording.error':
        case 'capture.unavailable':
          if (data.code === PERMISSION_MISSING) {
            setBanners((current) => ({ ...current, system_audio: SYSTEM_AUDIO_SETTINGS_URI }));
          } else {
            setError(`${text(data.code)}: ${text(data.message)}`);
          }
          break;
        case 'model.download':
          setModels((current) => current.map((m) => (m.id === data.id ? (data as unknown as ModelView) : m)));
          break;
      }
    };
    void client.subscribe(onEvent, controller.signal);
    void refreshMeetings(client).catch((e: unknown) => setError(String(e)));
    void client.models().then(setModels).catch((e: unknown) => setError(String(e)));
    return () => controller.abort();
  }, [client, refreshMeetings]);

  const status = useMemo(() => barState(state), [state]);
  const busy = ACTIVE_STATES.has(state);

  const record = async () => {
    if (!client) return;
    setError(undefined);
    setBanners({});
    setRows([]);
    setState('starting');
    try {
      await client.startRecording();
      await refreshMeetings(client);
    } catch (e) {
      setState('failed');
      setError(e instanceof Error ? e.message : String(e));
      // The only way out of a missing model is the Models screen.
      if ((e as { code?: string }).code === MODEL_MISSING) setView('models');
    }
  };

  const stop = async () => {
    if (!client) return;
    try {
      await client.stopRecording();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const download = async (id: string) => {
    if (!client) return;
    setModels((current) => current.map((m) => (m.id === id ? { ...m, state: 'downloading', error: undefined } : m)));
    try {
      await client.downloadModel(id);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <main>
      <h1>Interline</h1>
      {!client && slowStart && <p>Starting Interline...</p>}
      <div className={`recording-bar${status === 'recording' ? ' live' : ''}`}>
        <button type="button" onClick={record} disabled={!client || busy}>Record</button>
        <button type="button" onClick={stop} disabled={!client || state !== 'recording'}>Stop</button>
        <button type="button" onClick={() => setView(view === 'models' ? 'live' : 'models')}>Models</button>
        <p role="status">{status}</p>
        <Meters levels={levels} />
      </div>
      {status === 'capture restarting' && <p role="alert">Capture restarting, audio is being recovered...</p>}
      {(Object.entries(banners) as [PermissionKind, string][]).map(([kind, uri]) => (
        <PermissionBanner key={kind} kind={kind} settingsUri={uri} />
      ))}
      {error && <p role="alert">{error}</p>}
      {view === 'models' ? (
        <Models models={models} onDownload={(id) => void download(id)} />
      ) : (
        <>
          <h2>Transcript</h2>
          <ul aria-label="Transcript">
            {rows.map((row) => (
              <li key={row.key} aria-busy={!row.final} className={row.final ? undefined : 'partial'}>
                <strong>{row.speaker}</strong> {row.text}
              </li>
            ))}
          </ul>
          <h2>Meetings</h2>
          <ul aria-label="Meetings">
            {meetings.map((meeting) => (
              <li key={meeting.id}>
                {meeting.title || meeting.id.slice(0, 8)} - {meeting.status}
              </li>
            ))}
          </ul>
        </>
      )}
    </main>
  );
}
