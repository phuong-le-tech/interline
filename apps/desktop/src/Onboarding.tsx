import { useEffect, useRef, useState } from 'react';
import type { SystemSettings } from './coreClient';
import { useI18n } from './i18n/useI18n';
import { Models } from './Models';
import { Permissions, AudioTest } from './settings/Permissions';
import { CalendarSettings } from './settings/CalendarSettings';
import { Providers } from './settings/Providers';
import type { SettingsProps } from './settings/Settings';

export function Onboarding(props: SettingsProps & { onComplete: () => void }) {
  const { t, errorMessage } = useI18n();
  const [step, setStep] = useState(0);
  const [configure, setConfigure] = useState(false);
  const [system, setSystem] = useState<SystemSettings>();
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, [step]);
  useEffect(() => {
    let alive = true;
    void props.client.systemSettings().then((value) => { if (alive) setSystem(value); }).catch((e: unknown) => { if (alive) setError(e); });
    return () => { alive = false; };
  }, [props.client]);
  const complete = async () => {
    if (busy) return;
    setBusy(true); setError('');
    try { await props.client.saveUiPreferences({ onboarding_done: true }); props.onComplete(); }
    catch (e) { setError(e); }
    finally { setBusy(false); }
  };
  return <section aria-label={t('welcome')} className="onboarding">
    <p>{t('step_count', { step: step + 1 })}</p>
    <h2 ref={heading} tabIndex={-1}>{t((['welcome', 'permissions', 'transcription', 'ai'] as const)[step])}</h2>
    {step === 0 && <>{(['promise_1', 'promise_2', 'promise_3', 'promise_4', 'promise_5'] as const).map((key) => <p key={key}>{t(key)}</p>)}</>}
    {step === 1 && <>
      <Permissions client={props.client} system={system} onUpdate={setSystem} />
      <AudioTest {...props} />
      <details><summary>{t('calendar')}</summary><CalendarSettings client={props.client} system={system} onUpdate={setSystem} /></details>
    </>}
    {step === 2 && <Models models={props.models} onDownload={props.onDownload} system={system} />}
    {step === 3 && <><p>{t('ai_optional')}</p>{configure && <Providers client={props.client} />}</>}
    {Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
    <div className="settings-actions">
      {step > 0 && <button type="button" disabled={busy} onClick={() => setStep(step - 1)}>{t('back')}</button>}
      {step < 3 ? <button type="button" onClick={() => setStep(step + 1)}>{t(step === 0 || (step === 2 && props.models.some((model) => model.kind === 'stt' && model.state === 'installed')) ? 'continue' : 'continue_without')}</button> : <>
        <button type="button" disabled={busy} onClick={() => { void complete(); }}>{t(configure ? 'finish' : 'later')}</button>
        {!configure && <button type="button" onClick={() => setConfigure(true)}>{t('configure')}</button>}
      </>}
    </div>
  </section>;
}
