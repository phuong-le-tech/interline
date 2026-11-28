// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CoreClient, type SystemSettings } from '../coreClient';
import { Onboarding } from '../Onboarding';
import { Settings, type SettingsProps } from './Settings';
import { setLanguage } from '../i18n/useI18n';
import { Models } from '../Models';

afterEach(() => { cleanup(); setLanguage('en'); });
const system: SystemSettings = { version: '0.1.0', ram_bytes: 8 * 1024 ** 3, free_bytes: 1024 ** 3, audio_bytes: 0, model_bytes: 0,
  capabilities: { capture: { streams: ['mic', 'system'] }, calendar: false }, permissions: { microphone: 'denied', system_audio: 'granted' } };
function props(): SettingsProps {
  const client = new CoreClient({ port: 1, token: 'sk-test-ui' });
  vi.spyOn(client, 'systemSettings').mockResolvedValue(system);
  vi.spyOn(client, 'icsSource').mockResolvedValue({ configured: false });
  vi.spyOn(client, 'providers').mockResolvedValue([]);
  vi.spyOn(client, 'saveUiPreferences').mockResolvedValue({ onboarding_done: true, language: 'en' });
  return { client, models: [], onDownload: vi.fn(), levels: { mic: 0, system: 0 }, active: false, onTestDisposed: vi.fn(), onError: vi.fn(), onChanged: vi.fn() };
}

describe('onboarding and settings accessible controls', () => {
  it.each(['absent', 'installed'] as const)('labels the model step correctly when the speech model is %s', (state) => {
    const options = props();
    options.models = [{ id: 'small', name: 'Small', kind: 'stt', state, sizeBytes: 100, downloadedBytes: state === 'installed' ? 100 : 0 }];
    render(<Onboarding {...options} onComplete={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue without' }));
    expect(screen.getByRole('button', { name: state === 'installed' ? 'Continue' : 'Continue without' })).toBeEnabled();
  });
  it('offers a smaller model after disk exhaustion and resumes a partial download only on request', () => {
    const onDownload = vi.fn();
    render(<Models system={system} onDownload={onDownload} models={[
      { id: 'small', name: 'Small', kind: 'stt', state: 'partial', sizeBytes: 100, downloadedBytes: 50 },
      { id: 'large', name: 'Large', kind: 'stt', state: 'failed', sizeBytes: 1000, downloadedBytes: 100, error: 'No space left on device' },
    ]} />);
    expect(screen.getByRole('status')).toHaveTextContent('Choose the smaller model: Small');
    expect(screen.getByText('partial')).toHaveAttribute('aria-live', 'polite');
    expect(onDownload).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Resume' }));
    expect(onDownload).toHaveBeenCalledExactlyOnceWith('small');
  });
  it('all onboarding steps remain completable without optional permissions, downloads or AI', async () => {
    const options = props(); const onComplete = vi.fn();
    render(<Onboarding {...options} onComplete={onComplete} />);
    expect(screen.getByRole('heading', { name: 'Welcome to Interline' })).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(await screen.findByRole('link', { name: 'Open System Settings' })).toHaveAttribute('href', expect.stringContaining('x-apple.systempreferences:'));
    expect(screen.queryByRole('button', { name: 'Allow Calendar' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Continue without' }));
    expect(screen.getByRole('heading', { name: 'Transcription' })).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: 'Continue without' }));
    expect(screen.getByRole('button', { name: 'Later' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Configure' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Later' }));
    await waitFor(() => expect(onComplete).toHaveBeenCalledOnce());
    expect(options.client.saveUiPreferences).toHaveBeenCalledWith({ onboarding_done: true });
    expect(options.onDownload).not.toHaveBeenCalled();
  });
  it('failed persistence stays visible and retryable', async () => {
    const options = props(); const onComplete = vi.fn();
    vi.mocked(options.client.saveUiPreferences).mockRejectedValueOnce(new Error('Disk unavailable'));
    render(<Onboarding {...options} onComplete={onComplete} />);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue without' }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue without' }));
    fireEvent.click(screen.getByRole('button', { name: 'Later' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong. Please try again.');
    expect(screen.getByRole('alert')).not.toHaveTextContent('Disk unavailable');
    expect(onComplete).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Later' }));
    await waitFor(() => expect(onComplete).toHaveBeenCalledOnce());
  });
  it('labels settings forms, preserves write-only keys and changes language without losing controls', async () => {
    const options = props();
    vi.spyOn(options.client, 'createProvider').mockResolvedValue({ id: 'one', name: 'Example', kind: 'openai_compatible', base_url: 'https://example.com/v1', model: 'test', has_key: true, is_default: true, supports_json_schema: false });
    vi.spyOn(options.client, 'saveProviderKey').mockResolvedValue();
    render(<Settings {...options} />);
    fireEvent.change(screen.getByLabelText('Language'), { target: { value: 'fr' } });
    await waitFor(() => expect(screen.getByLabelText('Langue')).toHaveValue('fr'));
    fireEvent.click(screen.getByRole('button', { name: 'IA' }));
    fireEvent.change(screen.getByLabelText('Nom du fournisseur'), { target: { value: 'Example' } });
    fireEvent.change(screen.getByLabelText('URL de base'), { target: { value: 'https://example.com/v1' } });
    fireEvent.change(screen.getByLabelText('Modèle', { exact: true }), { target: { value: 'test' } });
    fireEvent.change(screen.getByLabelText('Clé API'), { target: { value: 'sk-test-private' } });
    fireEvent.click(screen.getByRole('button', { name: 'Enregistrer' }));
    await waitFor(() => expect(options.client.saveProviderKey).toHaveBeenCalledWith('one', 'sk-test-private'));
    expect(screen.getByLabelText('Clé API')).toHaveValue('');
    expect(document.body.textContent).not.toContain('sk-test-private');
    expect(await screen.findByRole('status')).toHaveTextContent('Enregistré.');
  });
});
