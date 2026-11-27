import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { CoreClient, inTauri, waitForEndpoint, type CoreEvent, type Meeting, type ModelView } from './coreClient';
import {
  EMPTY_TRANSCRIPT,
  CALENDAR_SETTINGS_URI,
  MICROPHONE_SETTINGS_URI,
  SYSTEM_AUDIO_SETTINGS_URI,
  applyLiveEvent,
  barState,
  type PermissionKind,
  type LiveTranscript,
} from './liveState';
import { Meters, type Levels } from './Meters';
import { Models } from './Models';
import { PermissionBanner } from './PermissionBanner';
import { Notes } from './notes/Notes';
import { Palette } from './notes/Palette';
import { Today } from './Today';
import { Consent } from './Consent';
import { trayState } from './trayState';

const SLOW_START_MS = 1500;
const ACTIVE_STATES = new Set(['starting', 'recording', 'degraded', 'processing']);
const PERMISSION_MISSING = 'permission_missing';
const MODEL_MISSING = 'model_missing';
const SILENT_LEVELS: Levels = { mic: 0, system: 0 };

const MAX_RECONNECT_BACKOFF_MS = 8000;

const text = (value: unknown): string => (typeof value === 'string' ? value : '');

export function App() {
  const [client, setClient] = useState<CoreClient>();
  const [slowStart, setSlowStart] = useState(false);
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [transcript, setTranscript] = useState<LiveTranscript>(EMPTY_TRANSCRIPT);
  const [models, setModels] = useState<ModelView[]>([]);
  const [levels, setLevels] = useState<Levels>(SILENT_LEVELS);
  const [banners, setBanners] = useState<Partial<Record<PermissionKind, string>>>({});
  const [state, setState] = useState('idle');
  const [error, setError] = useState<string>();
  const [view, setView] = useState<'live' | 'models' | 'notes'>('live');
  const [selectedMeetingId, setSelectedMeetingId] = useState<string>();
  const [highlightedSegmentId, setHighlightedSegmentId] = useState<number | undefined>();
  const [coreAlive, setCoreAlive] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [upcoming, setUpcoming] = useState<{ eventId: string; title: string }>();
  const [consentMeetingId, setConsentMeetingId] = useState<string | null>();
  const preparingRecording = useRef(false);

  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => setSlowStart(true), SLOW_START_MS);
    void waitForEndpoint(controller.signal).then((endpoint) => {
      clearTimeout(timer);
      if (endpoint) {
        setClient(new CoreClient(endpoint));
        setCoreAlive(true);
      }
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
    let reconnectBackoff = 1000;
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
        case 'stt.final':
        case 'transcript.suppressed':
          setTranscript((current) => applyLiveEvent(current, event.name, data));
          break;
        case 'calendar.denied':
          setBanners((current) => ({ ...current, calendar: CALENDAR_SETTINGS_URI }));
          break;
        case 'calendar.granted':
          setBanners((current) => {
            const next = { ...current };
            delete next.calendar;
            return next;
          });
          break;
        case 'permission.denied': {
          const kind: PermissionKind = data.permission === 'calendar' ? 'calendar' : data.permission === 'microphone' ? 'microphone' : 'system_audio';
          const fallback = kind === 'calendar' ? CALENDAR_SETTINGS_URI : kind === 'microphone' ? MICROPHONE_SETTINGS_URI : SYSTEM_AUDIO_SETTINGS_URI;
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
        case 'calendar.upcoming': {
          const title = text(data.title);
          setUpcoming({ eventId: text(data.eventId), title });
          if (inTauri()) void invoke('notify', { title, body: 'Starts in 1 minute' }).catch(() => undefined);
          break;
        }
        case 'model.download':
          setModels((current) => current.map((m) => (m.id === data.id ? (data as unknown as ModelView) : m)));
          break;
      }
    };
    const keepAlive = () => {
      setCoreAlive(true);
      reconnectBackoff = 1000;
    };
    const runSubscribe = async () => {
      while (!controller.signal.aborted) {
        try {
          await client.subscribe((event) => {
            keepAlive();
            onEvent(event);
          }, controller.signal);
        } catch {
          if (controller.signal.aborted) return;
        }
        setCoreAlive(false);
        await new Promise((done) => setTimeout(done, reconnectBackoff));
        reconnectBackoff = Math.min(reconnectBackoff * 2, MAX_RECONNECT_BACKOFF_MS);
      }
    };
    void runSubscribe();
    void refreshMeetings(client).catch((e: unknown) => setError(String(e)));
    void client.models().then(setModels).catch((e: unknown) => setError(String(e)));
    return () => controller.abort();
  }, [client, refreshMeetings]);

  useEffect(() => {
    if (!client) return;
    const controller = new AbortController();
    const heartbeat = async () => {
      try {
        await client.meetings();
        setCoreAlive(true);
      } catch {
        setCoreAlive(false);
      }
    };
    heartbeat();
    const id = setInterval(heartbeat, 2000);
    return () => {
      controller.abort();
      clearInterval(id);
    };
  }, [client]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((open) => !open);
      }
      if (e.key === 'Escape') setPaletteOpen(false);
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const status = useMemo(() => barState(state), [state]);
  const busy = ACTIVE_STATES.has(state);
  const tray = trayState(state, banners);
  const lastTray = useRef<typeof tray>(undefined);

  useEffect(() => {
    if (!inTauri() || lastTray.current === tray) return;
    lastTray.current = tray;
    void invoke('set_tray_state', { state: tray }).catch((e: unknown) => setError(`Tray indicator unavailable: ${String(e)}`));
  }, [tray]);

  const record = async () => {
    if (!client || preparingRecording.current || consentMeetingId !== undefined || busy) return;
    preparingRecording.current = true;
    setError(undefined);
    try {
      const settings = await client.consentSettings();
      const id = selectedMeetingId;
      const currentMeetings = await client.meetings();
      setMeetings(currentMeetings);
      const meeting = currentMeetings.find((meeting) => meeting.id === id);
      if (id && !meeting) throw new Error('Unknown meeting');
      if (settings.reminder_enabled && meeting?.consentAckAt == null) {
        setConsentMeetingId(id ?? null);
      } else {
        await startRecording(id ?? (await client.createMeeting('Untitled meeting')).id);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      preparingRecording.current = false;
    }
  };

  const startRecording = async (meetingId: string) => {
    if (!client) return;
    setSelectedMeetingId(meetingId);
    setBanners({});
    setTranscript(EMPTY_TRANSCRIPT);
    setState('starting');
    try {
      await client.startRecording(meetingId);
      await refreshMeetings(client);
    } catch (e) {
      setState('failed');
      setError(e instanceof Error ? e.message : String(e));
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

  const openMeetingNotes = (id: string, segmentId?: number) => {
    setSelectedMeetingId(id);
    setHighlightedSegmentId(segmentId);
    setView('notes');
  };

  return (
    <main>
      <h1>Interline</h1>
      {!client && slowStart && <p>Starting Interline...</p>}
      <div className={`recording-bar${status === 'recording' ? ' live' : ''}`}>
        <button type="button" onClick={record} disabled={!client || busy}>Record</button>
        <button type="button" onClick={stop} disabled={!client || state !== 'recording'}>Stop</button>
        <button type="button" onClick={() => setView(view === 'models' ? 'live' : 'models')}>Models</button>
        <button type="button" onClick={() => setPaletteOpen(true)}>Search</button>
        <p role="status">{status}</p>
        <Meters levels={levels} />
      </div>
      {status === 'capture restarting' && <p role="alert">Capture restarting, audio is being recovered...</p>}
      {(Object.entries(banners) as [PermissionKind, string][]).map(([kind, uri]) => (
        <PermissionBanner key={kind} kind={kind} settingsUri={uri} />
      ))}
      {error && <p role="alert">{error}</p>}
      {upcoming && client && (
        <p role="status" aria-label="Upcoming meeting">
          {upcoming.title} starts in 1 minute{' '}
          <button type="button" onClick={() => {
            void client.meetingFromEvent(upcoming.eventId).then(({ id }) => { setUpcoming(undefined); void refreshMeetings(client); openMeetingNotes(id); }).catch((e: unknown) => setError(String(e)));
          }}>Take notes</button>{' '}
          <button type="button" onClick={() => setUpcoming(undefined)}>Dismiss</button>
        </p>
      )}
      {view === 'models' ? (
        <Models models={models} onDownload={(id) => void download(id)} />
      ) : view === 'notes' && selectedMeetingId ? (
        <Notes client={client!} meetingId={selectedMeetingId} coreAlive={coreAlive} highlightedSegmentId={highlightedSegmentId} />
      ) : (
        <>
          <h2>Transcript</h2>
          <ul aria-label="Transcript">
            {transcript.rows.map((row) => (
              <li key={row.key} aria-busy={!row.final} className={row.final ? undefined : 'partial'}>
                <strong>{row.speaker}</strong> {row.text}
              </li>
            ))}
          </ul>
          {client && <Today client={client} onOpenMeeting={(id) => { void refreshMeetings(client); openMeetingNotes(id); }} />}
          <h2>Meetings</h2>
          <ul aria-label="Meetings">
            {meetings.map((meeting) => (
              <li key={meeting.id}>
                {meeting.title || meeting.id.slice(0, 8)} - {meeting.status}{' '}
                <button type="button" className="link" onClick={() => openMeetingNotes(meeting.id)} aria-label="Open notes">
                  notes
                </button>
              </li>
            ))}
          </ul>
        </>
      )}
      {paletteOpen && client && (
        <Palette
          client={client}
          onClose={() => setPaletteOpen(false)}
          onOpenMeeting={(id: string, segmentId?: number) => {
            setSelectedMeetingId(id);
            setHighlightedSegmentId(segmentId);
            setView('notes');
            setPaletteOpen(false);
          }}
        />
      )}
      {consentMeetingId !== undefined && client && (
        <Consent locale={document.documentElement.lang || navigator.language}
          onCancel={() => setConsentMeetingId(undefined)}
          onContinue={async (doNotRemind) => {
            const id = consentMeetingId ?? (await client.createMeeting('Untitled meeting')).id;
            setConsentMeetingId(id);
            setSelectedMeetingId(id);
            await client.acknowledgeConsent(id);
            if (doNotRemind) await client.setConsentReminder(false);
            setConsentMeetingId(undefined);
            await startRecording(id);
          }} />
      )}
    </main>
  );
}
