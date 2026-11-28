// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { CoreClient, type Provider } from '../coreClient';
import { Providers } from './Providers';
import { CalendarSettings } from './CalendarSettings';
import { setLanguage } from '../i18n/useI18n';

afterEach(() => { cleanup(); setLanguage('en'); });
const provider: Provider = { id: 'new', name: 'Example', kind: 'openai_compatible', base_url: 'https://example.com/v1', model: 'test', has_key: false, is_default: true, supports_json_schema: false };

it('removes a newly created provider when its key cannot be saved and permits a fresh retry', async () => {
  const client = new CoreClient({ port: 1, token: 'sk-test-review' });
  vi.spyOn(client, 'providers').mockResolvedValue([]);
  vi.spyOn(client, 'createProvider').mockResolvedValue(provider);
  vi.spyOn(client, 'saveProviderKey').mockRejectedValue(new Error('Key store offline'));
  vi.spyOn(client, 'deleteProvider').mockResolvedValue();
  vi.spyOn(client, 'updateProvider');
  render(<Providers client={client} />);
  for (const [label, value] of [['Provider name', 'Example'], ['Base URL', provider.base_url], ['Model', 'test'], ['API key', 'sk-test-review-key']])
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
  await waitFor(() => expect(client.deleteProvider).toHaveBeenCalledExactlyOnceWith('new'));
  expect(await screen.findByRole('alert')).toBeVisible();
  expect(screen.getByLabelText('API key')).toHaveValue('');
  fireEvent.click(screen.getByRole('button', { name: 'Save' }));
  await waitFor(() => expect(client.createProvider).toHaveBeenCalledTimes(2));
  expect(client.updateProvider).not.toHaveBeenCalled();
});

it('retains the typed private ICS address after a failed connection', async () => {
  const client = new CoreClient({ port: 1, token: 'sk-test-review' });
  vi.spyOn(client, 'icsSource').mockResolvedValue({ configured: false });
  vi.spyOn(client, 'setIcsSource').mockRejectedValue(new Error('Offline'));
  render(<CalendarSettings client={client} onUpdate={vi.fn()} />);
  const field = screen.getByLabelText('Calendar address (ICS)');
  fireEvent.change(field, { target: { value: 'https://example.com/private.ics' } });
  fireEvent.click(screen.getByRole('button', { name: 'Connect calendar' }));
  expect(await screen.findByRole('alert')).toBeVisible();
  expect(field).toHaveValue('https://example.com/private.ics');
});
