import { useI18n } from '../i18n/useI18n';
import { trapDialogFocus } from '../dialogFocus';
import { useEffect, useId, useRef, useState } from 'react';

export function DeleteConfirmation({ title, permanent, onConfirm, onCancel }: {
  title: string; permanent: boolean; onConfirm: () => Promise<void>; onCancel: () => void;
}) {
  const { t, errorMessage } = useI18n();
  const dialog = useRef<HTMLDialogElement>(null);
  const id = useId();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const submitting = useRef(false);
  useEffect(() => {
    const previous = document.activeElement;
    const element = dialog.current!;
    element.showModal();
    element.querySelector('button')?.focus();
    return () => { element.close(); if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);
  return <dialog ref={dialog} aria-modal="true" aria-labelledby={id} aria-describedby={`${id}-description`}
    onKeyDown={trapDialogFocus}
    onCancel={(event) => { event.preventDefault(); if (!submitting.current) onCancel(); }}>
    <h3 id={id}>{permanent ? t('permanently_delete') : t('move_trash')}: {title}</h3>
    <p id={`${id}-description`}>{permanent
      ? t('erase_description')
      : t('trash_description')}</p>
    {Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
    <button type="button" disabled={busy} onClick={onCancel}>{t('cancel')}</button>{' '}
    <button type="button" disabled={busy} onClick={() => {
      if (submitting.current) return;
      submitting.current = true; setBusy(true); setError('');
      void onConfirm().catch((e: unknown) => setError(e))
        .finally(() => { submitting.current = false; setBusy(false); });
    }}>{permanent ? t('delete_permanently') : t('move_trash')}</button>
  </dialog>;
}
