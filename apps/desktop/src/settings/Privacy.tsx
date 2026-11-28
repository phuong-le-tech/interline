import { en } from '../i18n/en';
import { useI18n, type MessageKey } from '../i18n/useI18n';
import { useEffect, useState } from 'react';
import type { PrivacyItem } from '../coreClient';

export function Privacy({ load }: { load: () => Promise<PrivacyItem[]> }) {
  const { t } = useI18n();
  const [items, setItems] = useState<PrivacyItem[]>([]);
  const [error, setError] = useState('');
  useEffect(() => {
    let current = true;
    void load().then((items) => { if (current) setItems(items); }).catch((e: unknown) => { if (current) setError(String(e)); });
    return () => { current = false; };
  }, [load]);
  const localize = (value: string) => {
    const key = (Object.keys(en) as MessageKey[]).find((key) => en[key] === value);
    return key ? t(key) : value;
  };
  return <section aria-label={t('what_leaves')}><h3>{t('what_leaves')}</h3>
    {error && <p role="alert">{error}</p>}
    <ul>{items.map((item) => <li key={item.id}>
      <strong>{localize(({ llm: en.privacy_llm, provider_test: en.privacy_provider_test, ics: en.privacy_ics, model_download: en.privacy_model_download, auto_update: en.privacy_auto_update, eventkit: en.privacy_eventkit, agent: en.privacy_agent } as Record<string, string>)[item.id.split(':')[0]] ?? item.id)}</strong>
      <dl><dt>{t('what_sent')}</dt><dd>{localize(item.what)}</dd><dt>{t('when')}</dt><dd>{localize(item.when)}</dd>
        <dt>{t('destination_host')}</dt><dd>{item.destinationHost ?? t('none')}</dd><dt>{t('state')}</dt><dd>{localize(item.state)}</dd></dl>
    </li>)}</ul>
  </section>;
}
