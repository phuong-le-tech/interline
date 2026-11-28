import { useState } from 'react';
import type { CoreClient, SystemSettings } from '../coreClient';
import { useI18n } from '../i18n/useI18n';
import { CALENDAR_SETTINGS_URI, MICROPHONE_SETTINGS_URI, SYSTEM_AUDIO_SETTINGS_URI, type PermissionKind } from '../liveState';
import { PermissionBanner } from '../PermissionBanner';
import { Meters, type Levels } from '../Meters';

export function Permissions({ client, system, onUpdate, calendar = false }: {
  client: CoreClient; system?: SystemSettings; onUpdate: (value: SystemSettings) => void; calendar?: boolean;
}) {
  const { t } = useI18n();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const streams = system?.capabilities?.capture?.streams ?? [];
  const kinds: PermissionKind[] = calendar ? (system?.capabilities?.calendar ? ['calendar'] : [])
    : [...(streams.includes('mic') ? ['microphone' as const] : []), ...(streams.includes('system') ? ['system_audio' as const] : [])];
  const uri = { microphone: MICROPHONE_SETTINGS_URI, system_audio: SYSTEM_AUDIO_SETTINGS_URI, calendar: CALENDAR_SETTINGS_URI };
  return <div>
    {(!system?.capabilities || system.agent_unavailable) && <p role="status">{t('agent_unavailable')}</p>}
    {error && <p role="alert">{error}</p>}
    {kinds.map((kind) => {
      const state = system?.permissions?.[kind] ?? 'unknown';
      return <section key={kind} aria-label={t(kind)}>
        <p>{t(kind)}: <span role="status">{permissionLabel(state, t)}</span></p>
        <button type="button" disabled={busy} onClick={() => {
          setBusy(true); setError('');
          void client.requestPermission(kind).then((permissions) => { if (system) onUpdate({ ...system, permissions }); })
            .catch((e: unknown) => setError(String(e))).finally(() => setBusy(false));
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

export function AudioTest({ levels, active, onRecord, onStop }: {
  levels: Levels; active: boolean; onRecord: () => void; onStop: () => void;
}) {
  const { t } = useI18n();
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
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
    } catch (e) { setError(String(e)); await context?.close(); }
  };
  return <div>
    <p>{t('audio_test_description')}</p>
    <Meters levels={levels} />
    <div className="settings-actions">
      <button type="button" disabled={active} onClick={onRecord}>{t('test_inputs')}</button>
      <button type="button" disabled={!active} onClick={onStop}>{t('stop')}</button>
      <button type="button" onClick={() => { void sound(); }}>{t('play_sound')}</button>
    </div>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </div>;
}
