import { en } from './i18n/en';
import { fr } from './i18n/fr';
import { detectLanguage, translate, useI18n, errorMessage as formatError } from './i18n/useI18n';
import { trapDialogFocus } from './dialogFocus';
import { useEffect, useId, useRef, useState } from 'react';

export const CONSENT_SENTENCES = { en: en.consent_sentence, fr: fr.consent_sentence };

export function Consent({ locale, onContinue, onCancel }: {
  locale?: string;
  onContinue: (doNotRemind: boolean) => Promise<void>;
  onCancel: () => void;
}) {
  const current = useI18n();
  const language = locale ? detectLanguage(locale) : current.language;
  const errorMessage = (error: unknown) => formatError(error, language);
  const t = (key: Parameters<typeof translate>[1]) => translate(language, key);
  const titleId = useId();
  const reminderId = useId();
  const dialog = useRef<HTMLDialogElement>(null);
  const submitting = useRef(false);
  const [doNotRemind, setDoNotRemind] = useState(false);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<unknown>();

  useEffect(() => {
    const previous = document.activeElement;
    const element = dialog.current!;
    element.showModal();
    element.querySelector<HTMLButtonElement>('button')?.focus();
    return () => {
      element.close();
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, []);

  const copy = async () => {
    setError(undefined);
    try {
      await navigator.clipboard.writeText(CONSENT_SENTENCES[language]);
      setCopied(true);
    } catch {
      setError({ code: 'clipboard_failed' });
    }
  };

  const proceed = async () => {
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setError(undefined);
    try {
      await onContinue(doNotRemind);
    } catch (e) {
      setError(e);
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  };

  return (
    <dialog ref={dialog} className="consent" aria-modal="true" aria-labelledby={titleId} aria-describedby={reminderId} lang={language}
      onCancel={(e) => { e.preventDefault(); if (!submitting.current) onCancel(); }}
      onKeyDown={(e) => {
        e.stopPropagation();
        if (e.key === 'Escape') {
          e.preventDefault();
          if (!submitting.current) onCancel();
        }
        trapDialogFocus(e);
      }}>
      <h2 id={titleId}>{t('consent_title')}</h2>
      <p id={reminderId}>{t('consent_reminder')}</p>
      <p>{CONSENT_SENTENCES[language]}</p>
      <button type="button" onClick={() => void copy()}>{t('copy')}</button>
      {copied && <p role="status">{t('copied')}</p>}
      <label><input type="checkbox" checked={doNotRemind} disabled={busy} onChange={(e) => setDoNotRemind(e.target.checked)} />{t('consent_checkbox')}</label>
      {Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
      <div className="consent-actions">
        <button type="button" disabled={busy} onClick={onCancel}>{t('cancel')}</button>
        <button type="button" disabled={busy} onClick={() => void proceed()}>{t('continue')}</button>
      </div>
    </dialog>
  );
}
