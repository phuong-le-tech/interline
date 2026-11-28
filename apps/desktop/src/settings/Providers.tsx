import { useEffect, useRef, useState } from 'react';
import type { CoreClient, Provider } from '../coreClient';
import { useI18n } from '../i18n/useI18n';

type Draft = Omit<Provider, 'id' | 'has_key'>;
const empty = (): Draft => ({ name: '', kind: 'openai_compatible', base_url: '', model: '', supports_json_schema: false, is_default: true });

export function Providers({ client }: { client: CoreClient }) {
  const { t, errorMessage } = useI18n();
  const [providers, setProviders] = useState<Provider[]>([]);
  const [editing, setEditing] = useState<string>();
  const [draft, setDraft] = useState<Draft>(empty);
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState<unknown>();
  const submitting = useRef(false);
  useEffect(() => {
    let alive = true;
    void client.providers().then((value) => { if (alive) setProviders(value); }).catch((e: unknown) => { if (alive) setError(e); });
    return () => { alive = false; };
  }, [client]);
  const run = async (action: () => Promise<void>) => {
    if (submitting.current) return;
    submitting.current = true; setBusy(true); setError(''); setMessage('');
    try { await action(); setProviders(await client.providers()); }
    catch (e) { setError(e); }
    finally { submitting.current = false; setBusy(false); }
  };
  const test = async (id: string) => {
    const result = await client.testProvider(id);
    setMessage(t(result.status === 'ok' ? 'provider_ok' : 'provider_error'));
  };
  return <section aria-label={t('providers')}>
    <p>{t('ai_optional')}</p>
    <ul>{providers.map((provider) => <li key={provider.id}>
      {provider.name} - {provider.model}{provider.is_default && ` (${t('default_provider')})`}{' '}
      <button type="button" disabled={busy} onClick={() => {
        setEditing(provider.id); setDraft({ name: provider.name, kind: provider.kind, base_url: provider.base_url,
          model: provider.model, supports_json_schema: provider.supports_json_schema, is_default: provider.is_default,
          context_tokens: provider.context_tokens, price_in_per_mtok: provider.price_in_per_mtok, price_out_per_mtok: provider.price_out_per_mtok });
        setKey(''); setMessage(''); setError('');
      }}>{t('edit')}</button>{' '}
      <button type="button" disabled={busy} onClick={() => { void run(() => test(provider.id)); }}>{t('test_connection')}</button>{' '}
      {provider.has_key && <button type="button" disabled={busy} onClick={() => { void run(async () => { await client.removeProviderKey(provider.id); setMessage(t('saved')); }); }}>{t('remove_key')}</button>}
    </li>)}</ul>
    <h3>{editing ? t('edit') : t('add_provider')}</h3>
    <form onSubmit={(event) => {
      event.preventDefault(); const secret = key; setKey('');
      void run(async () => {
        const provider = editing ? await client.updateProvider(editing, draft) : await client.createProvider(draft);
        try {
          if (secret) await client.saveProviderKey(provider.id, secret);
        } catch (error) {
          if (!editing) await client.deleteProvider(provider.id);
          throw error;
        }
        setEditing(provider.id);
        setMessage(t('saved'));
      });
    }}>
      <label>{t('provider_kind')}<select value={draft.kind} disabled={busy} onChange={(e) => setDraft({ ...draft, kind: e.target.value,
        ...(e.target.value === 'ollama' ? { name: 'Ollama', base_url: 'http://127.0.0.1:11434' } : {}) })}>
        <option value="openai_compatible">{t('openai_compatible')}</option><option value="ollama">Ollama</option><option value="anthropic">Anthropic</option>
      </select></label>
      <label>{t('provider_name')}<input required value={draft.name} disabled={busy} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
      <label>{t('base_url')}<input required type="url" value={draft.base_url} disabled={busy} onChange={(e) => setDraft({ ...draft, base_url: e.target.value })} /></label>
      <label>{t('model')}<input required value={draft.model} disabled={busy} onChange={(e) => setDraft({ ...draft, model: e.target.value })} /></label>
      {draft.kind === 'ollama' && <p>{t('ollama_description')}</p>}
      {draft.kind !== 'ollama' && <label>{t('api_key')}<input type="password" autoComplete="new-password" value={key} disabled={busy} onChange={(e) => setKey(e.target.value)} /></label>}
      <p>{t('key_write_only')}</p>
      {providers.find((p) => p.id === editing)?.has_key && <p>{t('key_stored')}</p>}
      <label><input type="checkbox" checked={draft.is_default} disabled={busy} onChange={(e) => setDraft({ ...draft, is_default: e.target.checked })} />{t('default_provider')}</label>
      <label><input type="checkbox" checked={draft.supports_json_schema} disabled={busy} onChange={(e) => setDraft({ ...draft, supports_json_schema: e.target.checked })} />{t('json_schema')}</label>
      <div className="settings-actions">
        <button type="submit" disabled={busy}>{t('save')}</button>
        {editing && <button type="button" disabled={busy} onClick={() => { setEditing(undefined); setDraft(empty()); setKey(''); }}>{t('add_provider')}</button>}
        {editing && <button type="button" disabled={busy} onClick={() => { void run(() => test(editing)); }}>{t('test_connection')}</button>}
      </div>
    </form>
    {message && <p role="status">{message}</p>}{Boolean(error) && <p role="alert">{errorMessage(error)}</p>}
  </section>;
}
