import { useEffect, useState } from 'react';
import type { Brief as BriefData, CoreClient } from '../coreClient';

/** The pre-meeting brief, collapsed to one line. It is an aid: when it cannot load or has nothing, it stays out of the way. */
export function Brief({ client, meetingId }: { client: CoreClient; meetingId: string }) {
  const [brief, setBrief] = useState<BriefData>();

  useEffect(() => {
    let alive = true;
    setBrief(undefined);
    client.brief(meetingId).then((b) => alive && setBrief(b)).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [client, meetingId]);

  if (!brief || (brief.participants.length + brief.related.length + brief.openActions.length === 0)) return null;
  return (
    <details className="brief" aria-label="Brief">
      <summary>
        Brief: {brief.related.length} related, {brief.openActions.length} open actions
      </summary>
      {brief.participants.length > 0 && <p>With {brief.participants.map((p) => p.name).join(', ')}</p>}
      <ul aria-label="Related meetings">
        {brief.related.map((r) => (
          <li key={r.meetingId}>
            {r.title} ({new Date(r.date).toLocaleDateString()})
            {r.notes.length > 0 && <ul>{r.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>}
          </li>
        ))}
      </ul>
      <ul aria-label="Open actions">
        {brief.openActions.map((a) => (
          <li key={a.id}>
            {a.text}
            {a.ownerLabel ? ` - ${a.ownerLabel}` : ''}
            {a.dueDate ? ` (due ${a.dueDate})` : ''}
          </li>
        ))}
      </ul>
    </details>
  );
}
