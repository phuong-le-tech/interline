import { useEffect, useId, useRef, useState } from 'react';

export const CONSENT_SENTENCES = {
  en: 'I would like to record this meeting to help with my notes. Is everyone comfortable with that?',
  fr: 'Je souhaite enregistrer cette réunion pour ma prise de notes. Est-ce que tout le monde est d’accord ?',
};

const COPY = {
  en: { title: 'Before recording', reminder: "Recording may need every participant's agreement. Please inform everyone before continuing.", copy: 'Copy', copied: 'Copied', checkbox: 'Do not remind me again', continue: 'Continue', cancel: 'Cancel', clipboardError: 'Could not copy. You can select and copy the sentence yourself.' },
  fr: { title: 'Avant d’enregistrer', reminder: 'Un enregistrement peut nécessiter l’accord de chaque participant. Veuillez informer tout le monde avant de continuer.', copy: 'Copier', copied: 'Copié', checkbox: 'Ne plus me le rappeler', continue: 'Continuer', cancel: 'Annuler', clipboardError: 'Copie impossible. Vous pouvez sélectionner et copier la phrase vous-même.' },
};

export function Consent({ locale = navigator.language, onContinue, onCancel }: {
  locale?: string;
  onContinue: (doNotRemind: boolean) => Promise<void>;
  onCancel: () => void;
}) {
  const language = locale.toLowerCase().startsWith('fr') ? 'fr' : 'en';
  const text = COPY[language];
  const titleId = useId();
  const reminderId = useId();
  const dialog = useRef<HTMLDialogElement>(null);
  const submitting = useRef(false);
  const [doNotRemind, setDoNotRemind] = useState(false);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string>();

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
      setError(text.clipboardError);
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
      setError(e instanceof Error ? e.message : String(e));
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
        if (e.key === 'Tab') {
          const controls = [...e.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled)')];
          const first = controls[0];
          const last = controls[controls.length - 1];
          if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus(); }
          else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus(); }
        }
      }}>
      <h2 id={titleId}>{text.title}</h2>
      <p id={reminderId}>{text.reminder}</p>
      <p>{CONSENT_SENTENCES[language]}</p>
      <button type="button" onClick={() => void copy()}>{text.copy}</button>
      {copied && <p role="status">{text.copied}</p>}
      <label><input type="checkbox" checked={doNotRemind} disabled={busy} onChange={(e) => setDoNotRemind(e.target.checked)} />{text.checkbox}</label>
      {error && <p role="alert">{error}</p>}
      <div className="consent-actions">
        <button type="button" disabled={busy} onClick={onCancel}>{text.cancel}</button>
        <button type="button" disabled={busy} onClick={() => void proceed()}>{text.continue}</button>
      </div>
    </dialog>
  );
}
