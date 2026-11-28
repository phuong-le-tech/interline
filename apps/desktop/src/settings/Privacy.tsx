import { useEffect, useState } from 'react';
import type { PrivacyItem } from '../coreClient';

export function Privacy({ load }: { load: () => Promise<PrivacyItem[]> }) {
  const [items, setItems] = useState<PrivacyItem[]>([]);
  const [error, setError] = useState('');
  useEffect(() => {
    let current = true;
    void load().then((items) => { if (current) setItems(items); }).catch((e: unknown) => { if (current) setError(String(e)); });
    return () => { current = false; };
  }, [load]);
  return <section aria-label="What leaves your machine"><h3>What leaves your machine</h3>
    {error && <p role="alert">{error}</p>}
    <ul>{items.map((item) => <li key={item.id}>
      <strong>{item.id.split(':')[0].replaceAll('_', ' ')}</strong>
      <dl><dt>What is sent</dt><dd>{item.what}</dd><dt>When</dt><dd>{item.when}</dd>
        <dt>Destination host</dt><dd>{item.destinationHost ?? 'None'}</dd><dt>State</dt><dd>{item.state}</dd></dl>
    </li>)}</ul>
  </section>;
}
