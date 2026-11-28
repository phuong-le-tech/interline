import { useI18n } from '../i18n/useI18n';
import { useEffect, useState } from 'react';
import type { AudioRetention } from '../coreClient';

export function Retention({ load, save }: {
  load: () => Promise<{ audio_retention: AudioRetention }>;
  save: (value: AudioRetention) => Promise<unknown>;
}) {
  const { t } = useI18n();
  const [value, setValue] = useState<AudioRetention>('7d');
  const [busy, setBusy] = useState(true);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let current = true;
    void load().then((settings) => { if (current) setValue(settings.audio_retention); })
      .catch((e: unknown) => { if (current) setError(String(e)); })
      .finally(() => { if (current) setBusy(false); });
    return () => { current = false; };
  }, [load]);
  return <section aria-label={t('audio_storage')}>
    <h3>{t('audio_retention')}</h3>
    <label>{t('audio_retention')} <select value={value} disabled={busy} onChange={(event) => {
      const next = event.target.value as AudioRetention;
      setBusy(true); setError(''); setMessage('');
      void save(next).then(() => { setValue(next); setMessage(t('retention_saved')); })
        .catch((e: unknown) => setError(String(e))).finally(() => setBusy(false));
    }}>
      <option value="after_transcription">{t('delete_after_transcription')}</option>
      <option value="7d">{t('seven_days')}</option>
      <option value="30d">{t('thirty_days')}</option>
      <option value="forever">{t('keep_forever')}</option>
    </select></label>
    <p>{t('retention_description')}</p>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}
