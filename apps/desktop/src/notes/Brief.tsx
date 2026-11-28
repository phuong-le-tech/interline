import { useI18n } from '../i18n/useI18n';
import { useEffect, useRef, useState } from 'react';
import type { Brief as BriefData, CoreClient } from '../coreClient';
import { CitationChip } from './CitationChip';

/** The pre-meeting brief, collapsed to one line. It is an aid: when it cannot load or has nothing, it stays out of the way. */
export function Brief({ client, meetingId }: { client: CoreClient; meetingId: string }) {
  const { t, language, errorMessage } = useI18n();
  const [brief, setBrief] = useState<BriefData>();
  const [hasProvider, setHasProvider] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>();
  const current = useRef(meetingId);
  current.current = meetingId;

  useEffect(() => {
    let alive = true;
    setBrief(undefined);
    setHasProvider(false);
    setBusy(false);
    setError('');
    client.providers().then((p) => alive && setHasProvider(p.some((provider) => (provider.has_key || provider.kind === 'ollama'))))
      .catch(() => undefined);
    client.brief(meetingId).then((b) => alive && setBrief(b)).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [client, meetingId]);

  async function write() {
    if (!hasProvider || busy) return;
    const requested = meetingId;
    setBusy(true);
    setError('');
    try {
      const result = await client.writeBrief(requested);
      if (current.current !== requested) return;
      if (result.status === 'error' || result.status === 'invalid_output') setError({ code: result.status === 'invalid_output' ? 'invalid_output' : undefined });
      else {
        const fresh = await client.brief(requested);
        if (current.current === requested) setBrief(fresh);
      }
    } catch {
      if (current.current === requested) setError({});
    } finally {
      if (current.current === requested) setBusy(false);
    }
  }

  if (!brief || (brief.participants.length + brief.related.length + brief.openActions.length === 0)) return null;
  return (
    <details className="brief" aria-label={t('brief')}>
      <summary>
        {t('brief_summary', { related: brief.related.length, actions: brief.openActions.length })}
      </summary>
      <button type="button" disabled={!hasProvider || busy} onClick={() => void write()}
        title={!hasProvider ? t('configure_provider') : undefined} aria-busy={busy}>
        {t(brief.ai?.status === 'stale' ? 'refresh' : 'write_brief')}
      </button>
      {!hasProvider && <span>{t('configure_provider')}</span>}
      {brief.ai?.status === 'stale' && <p role="status">{t('brief_stale')}</p>}
      {Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
      {brief.ai?.status === 'ok' && brief.ai.lines.length > 0 && (
        <div className="ai-addition" aria-label={t('ai_brief')}>
          {brief.ai.lines.map((line, index) => (
            <p key={index}>
              {line.text}{' '}
              {line.sources.map((id) => {
                const source = brief.related.find((r) => r.meetingId === id);
                return source && <CitationChip key={id} segmentId={id} label={source.title}
                  onSelect={() => {
                    const target = document.getElementById(`brief-source-${id}`);
                    target?.scrollIntoView({ block: 'nearest' });
                    target?.focus();
                  }} />;
              })}
            </p>
          ))}
        </div>
      )}
      {brief.participants.length > 0 && <p>{t('with_people', { names: brief.participants.map((p) => p.name).join(', ') })}</p>}
      <ul aria-label={t('related_meetings')}>
        {brief.related.map((r) => (
          <li key={r.meetingId} id={`brief-source-${r.meetingId}`} tabIndex={-1}>
            {r.title} ({new Date(r.date).toLocaleDateString(language)})
            {r.notes.length > 0 && <ul>{r.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>}
          </li>
        ))}
      </ul>
      <ul aria-label={t('open_actions')}>
        {brief.openActions.map((a) => (
          <li key={a.id}>
            {a.text}
            {a.ownerLabel ? ` - ${a.ownerLabel}` : ''}
            {a.dueDate ? t('due_date', { date: a.dueDate }) : ''}
          </li>
        ))}
      </ul>
    </details>
  );
}
