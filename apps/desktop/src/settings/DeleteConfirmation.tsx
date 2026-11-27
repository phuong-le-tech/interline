import { useEffect, useId, useRef, useState } from 'react';

export function DeleteConfirmation({ title, permanent, onConfirm, onCancel }: {
  title: string; permanent: boolean; onConfirm: () => Promise<void>; onCancel: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const id = useId();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submitting = useRef(false);
  useEffect(() => {
    const previous = document.activeElement;
    const element = dialog.current!;
    element.showModal();
    element.querySelector('button')?.focus();
    return () => { element.close(); if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);
  return <dialog ref={dialog} aria-modal="true" aria-labelledby={id} aria-describedby={`${id}-description`}
    onCancel={(event) => { event.preventDefault(); if (!submitting.current) onCancel(); }}>
    <h3 id={id}>{permanent ? 'Permanently delete' : 'Move to trash'}: {title}</h3>
    <p id={`${id}-description`}>{permanent
      ? 'This permanently erases audio, transcript, notes, AI results and linked actions. It cannot be undone.'
      : 'This moves the meeting and its audio, transcript, notes, AI results and linked actions to trash. You can restore it for 30 days. After that, everything is permanently erased and cannot be undone.'}</p>
    {error && <p role="alert">{error}</p>}
    <button type="button" disabled={busy} onClick={onCancel}>Cancel</button>{' '}
    <button type="button" disabled={busy} onClick={() => {
      if (submitting.current) return;
      submitting.current = true; setBusy(true); setError('');
      void onConfirm().catch((e: unknown) => setError(String(e)))
        .finally(() => { submitting.current = false; setBusy(false); });
    }}>{permanent ? 'Delete permanently' : 'Move to trash'}</button>
  </dialog>;
}
