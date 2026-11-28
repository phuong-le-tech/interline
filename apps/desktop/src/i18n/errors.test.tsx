// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { CoreClient } from '../coreClient';
import { CalendarSettings } from '../settings/CalendarSettings';
import { errorMessage, setLanguage, translate } from './useI18n';

afterEach(() => { cleanup(); setLanguage('en'); });

it.each(['en', 'fr'] as const)('maps known core problem codes and hides unknown exception details in %s', (language) => {
  const messages = new Set<string>();
  for (const code of ['no_provider', 'secret_store_unavailable', 'calendar_not_configured', 'calendar_fetch_failed', 'meeting_not_deletable', 'meeting_not_restorable']) {
    const error = Object.assign(new Error('English core detail\n at internal.Stack'), { code });
    const message = errorMessage(error, language);
    expect(message).not.toContain('English core detail');
    expect(message).not.toContain('Error:');
    expect(message).not.toContain('internal.Stack');
    messages.add(message);
  }
  expect(messages.size).toBe(6);
  expect(errorMessage(new Error('Sensitive stack'), language)).toBe(translate(language, 'error_generic'));
  expect(errorMessage({ code: 'unknown_code', detail: 'Raw text' }, language)).toBe(translate(language, 'error_generic'));
  expect(errorMessage({ code: 'no_provider' }, language)).toBe(language === 'fr'
    ? 'Configurez un fournisseur d’IA dans les réglages, puis réessayez.'
    : 'Configure an AI provider in Settings, then try again.');
});

it.each(['en', 'fr'] as const)('renders a translated calendar error in %s without raw exception text', async (language) => {
  setLanguage(language);
  const client = new CoreClient({ port: 1, token: 'sk-test-errors' });
  vi.spyOn(client, 'icsSource').mockRejectedValue(Object.assign(new Error('English private core detail'), { code: 'secret_store_unavailable' }));
  render(<CalendarSettings client={client} onUpdate={vi.fn()} />);
  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent(errorMessage({ code: 'secret_store_unavailable' }, language));
  expect(alert).toHaveTextContent(language === 'fr' ? 'Le stockage sécurisé des clés est indisponible.' : 'Secure key storage is unavailable.');
  expect(alert).not.toHaveTextContent('English private core detail');
  expect(alert).not.toHaveTextContent('Error:');
});
