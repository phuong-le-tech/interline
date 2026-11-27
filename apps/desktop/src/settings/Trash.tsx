import { useEffect, useRef, useState } from 'react';
import type { TrashedMeeting } from '../coreClient';
import { DeleteConfirmation } from './DeleteConfirmation';

export function Trash({ load, restore, erase, onChanged }: {
  load: () => Promise<TrashedMeeting[]>; restore: (id: string) => Promise<void>;
  erase: (id: string) => Promise<void>; onChanged?: () => void;
}) {
  const [items, setItems] = useState<TrashedMeeting[]>([]);
  const [selected, setSelected] = useState<TrashedMeeting>();
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    let current = true;
    void load().then((items) => { if (current) setItems(items); }).catch((e: unknown) => { if (current) setError(String(e)); });
    return () => { current = false; };
  }, [load]);
  useEffect(() => { if (message) heading.current?.focus(); }, [message]);
  const refresh = async () => { setItems(await load()); onChanged?.(); heading.current?.focus(); };
  return <section aria-label="Trash"><h3 ref={heading} tabIndex={-1}>Trash</h3>
    <p>Meetings are permanently erased after 30 days in trash.</p>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
    <ul aria-label="Trashed meetings">{items.map((item) => <li key={item.id}>
      {item.title || 'Untitled meeting'}{' '}
      {item.deletionStarted && <span>Permanent deletion pending. Restore unavailable. </span>}
      <button type="button" disabled={busy || item.deletionStarted} aria-label={`Restore ${item.title || 'Untitled meeting'}`} onClick={() => {
        setBusy(true); setError(''); setMessage('');
        void restore(item.id).then(refresh).then(() => setMessage('Meeting restored.'))
          .catch((e: unknown) => setError(String(e))).finally(() => setBusy(false));
      }}>Restore</button>{' '}
      <button type="button" disabled={busy} aria-label={`Permanently delete ${item.title || 'Untitled meeting'}`} onClick={() => setSelected(item)}>Delete permanently</button>
    </li>)}</ul>
    {selected && <DeleteConfirmation title={selected.title || 'Untitled meeting'} permanent onCancel={() => setSelected(undefined)} onConfirm={async () => {
      setMessage(''); await erase(selected.id); await refresh(); setSelected(undefined); setMessage('Meeting permanently deleted.');
    }} />}
  </section>;
}
