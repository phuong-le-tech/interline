import { useEffect, useId, useRef, useState } from 'react';
import type { CoreClient, ModelView, SystemSettings } from '../coreClient';
import { setLanguage, translate, useI18n, type Language } from '../i18n/useI18n';
import { formatBytes } from '../liveState';
import { Models } from '../Models';
import type { Levels } from '../Meters';
import { PrivacyData } from './PrivacyData';
import { Permissions, AudioTest } from './Permissions';
import { Providers } from './Providers';
import { CalendarSettings } from './CalendarSettings';

const sections = ['general', 'audio', 'transcription', 'ai', 'calendar', 'privacy', 'storage', 'about'] as const;
type Section = typeof sections[number];
export type SettingsProps = { client: CoreClient; models: ModelView[]; onDownload: (id: string) => void;
  levels: Levels; active: boolean; onTestDisposed: (id: string) => void; onError: (error: unknown) => void; onChanged: () => void };

export function Settings(props: SettingsProps) {
  const { client } = props;
  const { t, language, errorMessage } = useI18n();
  const [section, setSection] = useState<Section>('general');
  const [system, setSystem] = useState<SystemSettings>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState<unknown>();
  const languageId = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, [section]);
  useEffect(() => {
    let current = true;
    void client.systemSettings().then((value) => { if (current) setSystem(value); }).catch((e: unknown) => { if (current) setError(e); });
    return () => { current = false; };
  }, [client, section]);
  return <section aria-label={t('settings')} className="settings-screen">
    <h2>{t('settings')}</h2>
    <nav aria-label={t('settings')} className="settings-nav">{sections.map((name) => <button key={name} type="button" aria-current={section === name ? 'page' : undefined}
      onClick={() => { setSection(name); setMessage(''); setError(''); }}>{t(name)}</button>)}</nav>
    <div className="settings-content">
      <h3 ref={heading} tabIndex={-1}>{t(section)}</h3>
      {section === 'general' && <>
        <label htmlFor={languageId}>{t('language')}</label><select id={languageId} value={language} disabled={busy} onChange={(event) => {
          const next = event.target.value as Language;
          setBusy(true); setError(''); setMessage('');
          void client.saveUiPreferences({ language: next }).then(() => { setLanguage(next); setMessage(translate(next, 'saved')); })
            .catch((e: unknown) => setError(e)).finally(() => setBusy(false));
        }}><option value="en">{t('english')}</option><option value="fr">{t('french')}</option></select>
        <h4>{t('shortcuts')}</h4><p>{t('shortcut_search')}</p>
      </>}
      {section === 'audio' && <><Permissions client={client} system={system} onUpdate={setSystem} /><AudioTest {...props} /></>}
      {section === 'transcription' && <Models models={props.models} onDownload={props.onDownload} system={system} />}
      {section === 'ai' && <Providers client={client} />}
      {section === 'calendar' && <CalendarSettings client={client} system={system} onUpdate={setSystem} />}
      {section === 'privacy' && <PrivacyData client={client} onChanged={props.onChanged} />}
      {section === 'storage' && system && <dl>
        <dt>{t('audio')}</dt><dd>{t('audio_used', { size: formatBytes(system.audio_bytes) })}</dd>
        <dt>{t('models')}</dt><dd>{t('models_used', { size: formatBytes(system.model_bytes) })}</dd>
        <dt>{t('storage')}</dt><dd>{t('disk_free', { size: formatBytes(system.free_bytes) })}</dd>
      </dl>}
      {section === 'about' && <><p>Interline {system?.version ?? t('unknown')}</p><p>{t('license')}</p></>}
      {message && <p role="status">{message}</p>}{Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
    </div>
  </section>;
}
