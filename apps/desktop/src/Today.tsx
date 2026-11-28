import { useI18n } from './i18n/useI18n';
import { useCallback, useEffect, useState } from 'react';
import type { CalendarEvent, CoreClient } from './coreClient';

const DAY_MS = 24 * 60 * 60 * 1000;

const message = (e: unknown): string => (e instanceof Error ? e.message : String(e));

function todayRange(): [number, number] {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  return [start.getTime(), start.getTime() + DAY_MS];
}

/** Today's events, the calendar link, and manual meetings. The calendar is fetched only when the user asks. */
export function Today({ client, onOpenMeeting }: { client: CoreClient; onOpenMeeting: (meetingId: string) => void }) {
  const { t, language } = useI18n();
  const [host, setHost] = useState<string>();
  const [events, setEvents] = useState<CalendarEvent[]>([]);
  const [url, setUrl] = useState('');
  const [title, setTitle] = useState('');
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const source = await client.icsSource();
    setHost(source.configured ? source.host : undefined);
    setEvents(source.configured ? await client.calendarEvents(...todayRange()) : []);
  }, [client]);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(undefined);
    try {
      await action();
      await load();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    load().catch((e: unknown) => setError(message(e)));
  }, [load]);

  const connect = () =>
    run(async () => {
      await client.setIcsSource(url);
      setUrl('');
      await client.syncCalendar();
    });

  const open = (action: () => Promise<{ id: string }>) =>
    run(async () => onOpenMeeting((await action()).id));

  return (
    <section aria-label={t('today')}>
      <h2>{t('today')}</h2>
      {error && <p role="alert">{error}</p>}
      <ul aria-label={t('today_events')} className="today-events">
        {events.map((event) => (
          <li key={event.id}>
            {new Date(event.starts_at).toLocaleTimeString(language, { hour: '2-digit', minute: '2-digit' })} {event.title}{' '}
            <button type="button" disabled={busy} onClick={() => void open(() => client.meetingFromEvent(event.id))}>
              {t('take_notes')}
            </button>
          </li>
        ))}
      </ul>
      {host && events.length === 0 && <p>{t('no_events')}</p>}
      {host ? (
        <p>
          {t('calendar')}: {host}{' '}
          <button type="button" disabled={busy} onClick={() => void run(() => client.syncCalendar())}>{t('sync')}</button>{' '}
          <button type="button" disabled={busy} onClick={() => void run(() => client.removeIcsSource())}>{t('disconnect')}</button>
        </p>
      ) : (
        <form onSubmit={(e) => { e.preventDefault(); void connect(); }}>
          <input type="password" autoComplete="off" aria-label={t('calendar_address')} placeholder={t('calendar_address')} value={url} onChange={(e) => setUrl(e.target.value)} />{' '}
          <button type="submit" disabled={busy || !url.trim()}>{t('connect_calendar')}</button>
        </form>
      )}
      <form onSubmit={(e) => { e.preventDefault(); const t = title.trim(); setTitle(''); void open(() => client.createMeeting(t)); }}>
        <input aria-label={t('meeting_title')} placeholder={t('meeting_title')} value={title} onChange={(e) => setTitle(e.target.value)} />{' '}
        <button type="submit" disabled={busy || !title.trim()}>{t('new_meeting')}</button>
      </form>
    </section>
  );
}
