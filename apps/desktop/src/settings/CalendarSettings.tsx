import { useEffect, useState } from 'react';
import type { CoreClient, SystemSettings } from '../coreClient';
import { useI18n } from '../i18n/useI18n';
import { Permissions, permissionLabel } from './Permissions';

export function CalendarSettings({ client, system, onUpdate }: { client: CoreClient; system?: SystemSettings; onUpdate: (value: SystemSettings) => void }) {
  const { t } = useI18n();
  const [source, setSource] = useState<{ configured: boolean; host?: string }>();
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  useEffect(() => {
    let current = true;
    void client.icsSource().then((value) => { if (current) setSource(value); }).catch((e: unknown) => { if (current) setError(String(e)); });
    return () => { current = false; };
  }, [client]);
  const run = async (action: () => Promise<unknown>) => {
    setBusy(true); setError(''); setMessage('');
    try { await action(); setSource(await client.icsSource()); setMessage(t('saved')); }
    catch (e) { setError(String(e)); }
    finally { setBusy(false); }
  };
  return <div>
    <p>{system?.capabilities?.calendar
      ? t('eventkit_status', { state: permissionLabel(system.permissions?.calendar ?? 'unknown', t) }) : t('eventkit_unavailable')}</p>
    {system?.capabilities?.calendar && <Permissions client={client} system={system} onUpdate={onUpdate} calendar />}
    {source?.configured ? <p>{t('calendar')}: {source.host}{' '}
      <button type="button" disabled={busy} onClick={() => { void run(() => client.removeIcsSource()); }}>{t('disconnect')}</button>{' '}
      <button type="button" disabled={busy} onClick={() => { void run(() => client.syncCalendar()); }}>{t('sync')}</button>
    </p> : <form onSubmit={(event) => { event.preventDefault(); void run(async () => { await client.setIcsSource(url); setUrl(''); }); }}>
      <label>{t('calendar_address')}<input type="password" autoComplete="off" disabled={busy} value={url} onChange={(e) => setUrl(e.target.value)} /></label>
      <button type="submit" disabled={busy || !url.trim()}>{t('connect_calendar')}</button>
    </form>}
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </div>;
}
