import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CoreClient, SystemSettings } from '../coreClient';
import { useI18n } from '../i18n/useI18n';
import { CALENDAR_SETTINGS_URI, MICROPHONE_SETTINGS_URI, SYSTEM_AUDIO_SETTINGS_URI, type PermissionKind } from '../liveState';
import { PermissionBanner } from '../PermissionBanner';
import { Meters, type Levels } from '../Meters';
import { Consent } from '../Consent';
import { AudioTestSession } from './audioTestSession';

export function Permissions({ client, system, onUpdate, calendar = false }: {
  client: CoreClient; system?: SystemSettings; onUpdate: (value: SystemSettings) => void; calendar?: boolean;
}) {
  const { t, errorMessage } = useI18n();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const streams = system?.capabilities?.capture?.streams ?? [];
  const kinds: PermissionKind[] = calendar ? (system?.capabilities?.calendar ? ['calendar'] : [])
    : [...(streams.includes('mic') ? ['microphone' as const] : []), ...(streams.includes('system') ? ['system_audio' as const] : [])];
  const uri = { microphone: MICROPHONE_SETTINGS_URI, system_audio: SYSTEM_AUDIO_SETTINGS_URI, calendar: CALENDAR_SETTINGS_URI };
  return <div>
    {(!system?.capabilities || system.agent_unavailable) && <p role="status">{t('agent_unavailable')}</p>}
    {Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
    {kinds.map((kind) => {
      const state = system?.permissions?.[kind] ?? 'unknown';
      return <section key={kind} aria-label={t(kind)}>
        <p>{t(kind)}: <span role="status">{permissionLabel(state, t)}</span></p>
        <button type="button" disabled={busy} onClick={() => {
          setBusy(true); setError('');
          void client.requestPermission(kind).then((permissions) => { if (system) onUpdate({ ...system, permissions }); })
            .catch((e: unknown) => setError(e)).finally(() => setBusy(false));
        }}>{t('request_permission', { name: t(kind) })}</button>
        {(state === 'denied' || state === 'restricted' || (kind === 'system_audio' && state === 'unknown')) && <PermissionBanner kind={kind} settingsUri={uri[kind]} />}
      </section>;
    })}
    <p>{t('permissions_optional')}</p>
  </div>;
}

export function permissionLabel(state: string, t: ReturnType<typeof useI18n>['t']) {
  switch (state) {
    case 'granted': return t('permission_granted');
    case 'denied': return t('permission_denied');
    case 'restricted': return t('permission_restricted');
    case 'not_determined': return t('permission_not_determined');
    default: return t('permission_unknown');
  }
}

export function AudioTest({ client, levels, active, onTestDisposed, onError }: {
  client: CoreClient; levels: Levels; active: boolean; onTestDisposed: (id: string) => void; onError: (error: unknown) => void;
}) {
  const { t, errorMessage } = useI18n();
  const [message, setMessage] = useState('');
  const [error, setError] = useState<unknown>();
  const [consent, setConsent] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const [testing, setTesting] = useState(false);
  const session = useMemo(() => new AudioTestSession(client), [client]);
  const mounted = useRef(true);
  const observedCapture = useRef(false);
  const callbacks = useRef({ onTestDisposed, onError });
  callbacks.current = { onTestDisposed, onError };
  const finish = useCallback(async () => {
    try {
      const id = await session.dispose();
      if (id) callbacks.current.onTestDisposed(id);
      if (mounted.current) { setTesting(false); observedCapture.current = false; }
    } catch (error) {
      if (mounted.current) setError(error);
      else callbacks.current.onError(error);
    }
  }, [session]);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; void finish(); };
  }, [finish]);
  useEffect(() => {
    if (testing && active) observedCapture.current = true;
    if (testing && observedCapture.current && !active) void finish();
  }, [active, testing, finish]);
  const start = async () => {
    setTesting(true); setError(undefined);
    try { await session.start(t('test_inputs')); setConsent(false); }
    catch (error) { await finish(); throw error; }
  };
  const sound = async () => {
    setError('');
    let context: AudioContext | undefined;
    try {
      context = new AudioContext();
      await context.resume();
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.frequency.value = 440;
      gain.gain.value = 0.1;
      oscillator.connect(gain).connect(context.destination);
      oscillator.start(); oscillator.stop(context.currentTime + 1);
      oscillator.onended = () => { void context?.close(); };
      setMessage(t('sound_played'));
    } catch (e) { setError(e); await context?.close(); }
  };
  return <section aria-label={t('test_inputs')}>
    <p>{t('audio_test_description')}</p>
    <Meters levels={levels} />
    <div className="settings-actions">
      <button type="button" disabled={active || testing || preparing} onClick={() => {
        setPreparing(true); setError(undefined);
        void client.consentSettings().then(async (settings) => {
          if (!mounted.current) return;
          if (settings.reminder_enabled) setConsent(true);
          else await start();
        }).catch(setError).finally(() => setPreparing(false));
      }}>{t('test_inputs')}</button>
      <button type="button" disabled={!testing} onClick={() => { void finish(); }}>{t('stop')}</button>
      <button type="button" onClick={() => { void sound(); }}>{t('play_sound')}</button>
    </div>
    {message && <p role="status">{message}</p>}{Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
    {consent && <Consent onCancel={() => setConsent(false)} onContinue={async (doNotRemind) => {
      if (doNotRemind) await client.setConsentReminder(false);
      await start();
    }} />}
  </section>;
}
