import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { CoreClient, inTauri, waitForEndpoint, type CoreEvent, type Meeting, type ModelView, type UiPreferences } from './coreClient';
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
import { Settings } from './settings/Settings';
import { Onboarding } from './Onboarding';
import { setLanguage, detectLanguage, useI18n, stateLabel } from './i18n/useI18n';
import { DeleteConfirmation } from './settings/DeleteConfirmation';
import { trayState } from './trayState';
import { trapDialogFocus } from './dialogFocus';

const SLOW_START_MS = 1500;
const ACTIVE_STATES = new Set(['starting', 'recording', 'degraded', 'processing']);
const PERMISSION_MISSING = 'permission_missing';
const MODEL_MISSING = 'model_missing';
const SILENT_LEVELS: Levels = { mic: 0, system: 0 };

const MAX_RECONNECT_BACKOFF_MS = 8000;

const text = (value: unknown): string => (typeof value === 'string' ? value : '');

export function App() {
  const { t, errorMessage } = useI18n();
  const translation = useRef(t);
  translation.current = t;
  const [preferences, setPreferences] = useState<UiPreferences>();
  const [client, setClient] = useState<CoreClient>();
  const [slowStart, setSlowStart] = useState(false);
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [transcript, setTranscript] = useState<LiveTranscript>(EMPTY_TRANSCRIPT);
  const [models, setModels] = useState<ModelView[]>([]);
  const [levels, setLevels] = useState<Levels>(SILENT_LEVELS);
  const [banners, setBanners] = useState<Partial<Record<PermissionKind, string>>>({});
  const [state, setState] = useState('idle');
  const [error, setError] = useState<unknown>();
  const [view, setView] = useState<'live' | 'models' | 'notes' | 'settings'>('live');
  const [selectedMeetingId, setSelectedMeetingId] = useState<string>();
  const [highlightedSegmentId, setHighlightedSegmentId] = useState<number | undefined>();
  const [coreAlive, setCoreAlive] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const [upcoming, setUpcoming] = useState<{ eventId: string; title: string }>();
  const [consentMeetingId, setConsentMeetingId] = useState<string | null>();
  const [deleting, setDeleting] = useState<Meeting>();
  const settingsButton = useRef<HTMLButtonElement>(null);
  const preparingRecording = useRef(false);
  const resumableDownloads = useRef(new Set<string>());
  const recordingMeeting = useRef('');

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

  useEffect(() => {
    if (!client) return;
    let alive = true;
    void client.uiPreferences().then(async (value) => {
      const language = value.language || detectLanguage(navigator.language);
      if (!value.language) await client.saveUiPreferences({ language });
      if (alive) { setLanguage(language); setPreferences({ ...value, language }); }
    }).catch((e: unknown) => { if (alive) setError(e); });
    return () => { alive = false; };
  }, [client]);

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
          recordingMeeting.current = text(data.meetingId);
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
            setError({ code: data.code });
          }
          break;
        case 'calendar.upcoming': {
          const title = text(data.title);
          setUpcoming({ eventId: text(data.eventId), title });
          if (inTauri()) void invoke('notify', { title, body: translation.current('starts_soon') }).catch(() => undefined);
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
    void refreshMeetings(client).catch((e: unknown) => setError(e));
    void client.models().then(setModels).catch((e: unknown) => setError(e));
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
      if ((e.metaKey || e.ctrlKey) && e.key === '/') { e.preventDefault(); setShortcutsOpen((open) => !open); }
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
    void invoke('set_tray_state', { state: tray }).catch((e: unknown) => setError(e));
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
      if (id && !meeting) throw new Error(t('unknown_meeting'));
      if (settings.reminder_enabled && meeting?.consentAckAt == null) {
        setConsentMeetingId(id ?? null);
      } else {
        await startRecording(id ?? (await client.createMeeting(t('untitled'))).id);
      }
    } catch (e) {
      setError(e);
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
      setError(e);
      if ((e as { code?: string }).code === MODEL_MISSING) setView('models');
    }
  };

  const stop = async () => {
    if (!client) return;
    try {
      await client.stopRecording();
    } catch (e) {
      setError(e);
    }
  };

  const download = async (id: string) => {
    if (!client) return;
    resumableDownloads.current.add(id);
    setModels((current) => current.map((m) => (m.id === id ? { ...m, state: 'downloading', error: undefined } : m)));
    try {
      await client.downloadModel(id);
    } catch (e) {
      setError(e);
    }
  };

  useEffect(() => {
    if (!client) return;
    for (const model of models) {
      if (model.state === 'installed') resumableDownloads.current.delete(model.id);
      if (model.state !== 'failed' || !resumableDownloads.current.delete(model.id)
        || model.downloadedBytes <= 0 || /checksum|no space|disk full/i.test(model.error ?? '')) continue;
      // shortcut: one automatic resume per explicit download; add bounded backoff if repeated interruptions become common.
      setModels((current) => current.map((value) => value.id === model.id ? { ...value, state: 'downloading', error: undefined } : value));
      void client.downloadModel(model.id).catch((e: unknown) => {
        setError(e);
        setModels((current) => current.map((value) => value.id === model.id && value.state === 'downloading'
          ? { ...value, state: 'failed', error: 'download_failed' } : value));
      });
    }
  }, [client, models]);

  const openMeetingNotes = (id: string, segmentId?: number) => {
    setSelectedMeetingId(id);
    setHighlightedSegmentId(segmentId);
    setView('notes');
  };

  const audioTestDisposed = (id: string) => {
    if (recordingMeeting.current === id) setTranscript(EMPTY_TRANSCRIPT);
    if (client) void refreshMeetings(client).catch(setError);
  };

  return (
    <main>
      <h1>Interline</h1>
      {!client && slowStart && <p>{t('starting_app')}</p>}
      <div className={`recording-bar${status === 'recording' ? ' live' : ''}`}>
        <button type="button" onClick={record} disabled={!client || busy}>{t('record')}</button>
        <button type="button" onClick={stop} disabled={!client || state !== 'recording'}>{t('stop')}</button>
        <button type="button" onClick={() => setView(view === 'models' ? 'live' : 'models')}>{t('models')}</button>
        <button type="button" onClick={() => setPaletteOpen(true)}>{t('search')}</button>
        <button ref={settingsButton} type="button" disabled={!client} onClick={() => {
          if (view === 'settings') { setView('live'); settingsButton.current?.focus(); } else setView('settings');
        }}>{t(view === 'settings' ? 'back_meetings' : 'settings')}</button>
        <p role="status">{stateLabel(status, t)}</p>
        <Meters levels={levels} />
      </div>
      {status === 'capture restarting' && <p role="alert">{t('capture_restarting')}</p>}
      {(Object.entries(banners) as [PermissionKind, string][]).map(([kind, uri]) => (
        <PermissionBanner key={kind} kind={kind} settingsUri={uri} />
      ))}
      {Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
      {upcoming && client && (
        <p role="status" aria-label={t('upcoming_meeting')}>
          {upcoming.title} {t('starts_soon')}{' '}
          <button type="button" onClick={() => {
            void client.meetingFromEvent(upcoming.eventId).then(({ id }) => { setUpcoming(undefined); void refreshMeetings(client); openMeetingNotes(id); }).catch((e: unknown) => setError(e));
          }}>{t('take_notes')}</button>{' '}
          <button type="button" onClick={() => setUpcoming(undefined)}>{t('dismiss')}</button>
        </p>
      )}
      {client && preferences && !preferences.onboarding_done ? (
        <Onboarding client={client} models={models} onDownload={(id) => { void download(id); }} levels={levels} active={busy}
          onTestDisposed={audioTestDisposed} onError={setError} onChanged={() => { void refreshMeetings(client); }}
          onComplete={() => setPreferences({ ...preferences, onboarding_done: true })} />
      ) : view === 'settings' && client ? (
        <Settings client={client} models={models} onDownload={(id) => { void download(id); }} levels={levels} active={busy}
          onTestDisposed={audioTestDisposed} onError={setError} onChanged={() => { void refreshMeetings(client); }} />
      ) : view === 'models' ? (
        <Models models={models} onDownload={(id) => void download(id)} />
      ) : view === 'notes' && selectedMeetingId ? (
        <Notes client={client!} meetingId={selectedMeetingId} coreAlive={coreAlive} highlightedSegmentId={highlightedSegmentId} />
      ) : (
        <>
          <h2>{t('transcript')}</h2>
          <ul aria-label={t('transcript')} aria-live="polite">
            {transcript.rows.map((row) => (
              <li key={row.key} aria-busy={!row.final} className={row.final ? undefined : 'partial'}>
                <strong>{row.speaker}</strong> {row.text}
              </li>
            ))}
          </ul>
          {client && <Today client={client} onOpenMeeting={(id) => { void refreshMeetings(client); openMeetingNotes(id); }} />}
          <h2>{t('meetings')}</h2>
          <ul aria-label={t('meetings')}>
            {meetings.map((meeting) => (
              <li key={meeting.id}>
                {meeting.title || meeting.id.slice(0, 8)} - {stateLabel(meeting.status, t)}{' '}
                <button type="button" className="link" onClick={() => openMeetingNotes(meeting.id)} aria-label={t('open_notes')}>
                  {t('notes')}
                </button>{' '}
                <button type="button" disabled={busy} aria-label={t('delete_named', { name: meeting.title || t('untitled') })} onClick={() => setDeleting(meeting)}>{t('delete')}</button>
              </li>
            ))}
          </ul>
        </>
      )}
      {deleting && client && <DeleteConfirmation title={deleting.title || t('untitled')} permanent={false} onCancel={() => setDeleting(undefined)} onConfirm={async () => {
        await client.deleteMeeting(deleting.id);
        if (selectedMeetingId === deleting.id) setSelectedMeetingId(undefined);
        await refreshMeetings(client); setDeleting(undefined); requestAnimationFrame(() => settingsButton.current?.focus());
      }} />}
      {paletteOpen && client && (
        <Palette
          onOpenSettings={() => { setView('settings'); setPaletteOpen(false); }}
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
      {shortcutsOpen && <Shortcuts onClose={() => setShortcutsOpen(false)} />}
      {consentMeetingId !== undefined && client && (
        <Consent locale={document.documentElement.lang || navigator.language}
          onCancel={() => setConsentMeetingId(undefined)}
          onContinue={async (doNotRemind) => {
            const id = consentMeetingId ?? (await client.createMeeting(t('untitled'))).id;
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

function Shortcuts({ onClose }: { onClose: () => void }) {
  const { t } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    const element = dialog.current!;
    element.showModal();
    return () => { element.close(); if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);
  return <dialog ref={dialog} aria-modal="true" aria-label={t('shortcuts')} onKeyDown={trapDialogFocus} onCancel={(e) => { e.preventDefault(); onClose(); }}>
    <h2>{t('shortcuts')}</h2><p>{t('shortcut_search')}</p><button type="button" onClick={onClose}>{t('dismiss')}</button>
  </dialog>;
}
