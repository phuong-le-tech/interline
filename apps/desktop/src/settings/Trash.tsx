import { useI18n } from '../i18n/useI18n';
import { useEffect, useRef, useState } from 'react';
import type { TrashedMeeting } from '../coreClient';
import { DeleteConfirmation } from './DeleteConfirmation';

export function Trash({ load, restore, erase, onChanged }: {
  load: () => Promise<TrashedMeeting[]>; restore: (id: string) => Promise<void>;
  erase: (id: string) => Promise<void>; onChanged?: () => void;
}) {
  const { t, errorMessage } = useI18n();
  const [items, setItems] = useState<TrashedMeeting[]>([]);
  const [selected, setSelected] = useState<TrashedMeeting>();
  const [message, setMessage] = useState('');
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    let current = true;
    void load().then((items) => { if (current) setItems(items); }).catch((e: unknown) => { if (current) setError(e); });
    return () => { current = false; };
  }, [load]);
  useEffect(() => { if (message) heading.current?.focus(); }, [message]);
  const refresh = async () => { setItems(await load()); onChanged?.(); heading.current?.focus(); };
  return <section aria-label={t('trash')}><h3 ref={heading} tabIndex={-1}>{t('trash')}</h3>
    <p>{t('trash_expiry')}</p>
    {message && <p role="status">{message}</p>}{Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
    <ul aria-label={t('trashed_meetings')}>{items.map((item) => <li key={item.id}>
      {item.title || t('untitled')}{' '}
      {item.deletionStarted && <span>{t('deletion_pending')}</span>}
      <button type="button" disabled={busy || item.deletionStarted} aria-label={t('restore_named', { name: item.title || t('untitled') })} onClick={() => {
        setBusy(true); setError(''); setMessage('');
        void restore(item.id).then(refresh).then(() => setMessage(t('meeting_restored')))
          .catch((e: unknown) => setError(e)).finally(() => setBusy(false));
      }}>{t('restore')}</button>{' '}
      <button type="button" disabled={busy} aria-label={t('erase_named', { name: item.title || t('untitled') })} onClick={() => setSelected(item)}>{t('delete_permanently')}</button>
    </li>)}</ul>
    {selected && <DeleteConfirmation title={selected.title || t('untitled')} permanent onCancel={() => setSelected(undefined)} onConfirm={async () => {
      setMessage(''); await erase(selected.id); await refresh(); setSelected(undefined); setMessage(t('meeting_erased'));
    }} />}
  </section>;
}
