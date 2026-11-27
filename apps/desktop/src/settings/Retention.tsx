import { useEffect, useState } from 'react';
import type { AudioRetention } from '../coreClient';

export function Retention({ load, save }: {
  load: () => Promise<{ audio_retention: AudioRetention }>;
  save: (value: AudioRetention) => Promise<unknown>;
}) {
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
  return <section aria-label="Audio storage">
    <h3>Audio retention</h3>
    <label>Audio retention <select value={value} disabled={busy} onChange={(event) => {
      const next = event.target.value as AudioRetention;
      setBusy(true); setError(''); setMessage('');
      void save(next).then(() => { setValue(next); setMessage('Audio retention saved.'); })
        .catch((e: unknown) => setError(String(e))).finally(() => setBusy(false));
    }}>
      <option value="after_transcription">Delete after transcription</option>
      <option value="7d">7 days after the meeting</option>
      <option value="30d">30 days after the meeting</option>
      <option value="forever">Keep forever</option>
    </select></label>
    <p>Applies when a meeting next ends. Notes and transcripts stay until you delete the meeting.</p>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}
