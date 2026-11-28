// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Retention } from './Retention';
import { Trash } from './Trash';
import { Privacy } from './Privacy';
import { UpdateCheck } from './UpdateCheck';
import { FileVaultNotice } from './FileVaultNotice';

afterEach(cleanup);
HTMLDialogElement.prototype.showModal = function () { this.open = true; };
HTMLDialogElement.prototype.close = function () { this.open = false; };

describe('Privacy and data components', () => {
  it('loads and saves the labelled retention control', async () => {
    const save = vi.fn().mockResolvedValue({ audio_retention: '30d' });
    render(<Retention load={async () => ({ audio_retention: '7d' })} save={save} />);
    await waitFor(() => expect(screen.getByLabelText('Audio retention')).toBeEnabled());
    expect(screen.getByLabelText('Audio retention')).toHaveValue('7d');
    fireEvent.change(screen.getByLabelText('Audio retention'), { target: { value: '30d' } });
    await waitFor(() => expect(save).toHaveBeenCalledWith('30d'));
    expect(await screen.findByText('Audio retention saved.')).toHaveAttribute('role', 'status');
  });
  it('lists trash and restores without a destructive confirmation', async () => {
    const restore = vi.fn().mockResolvedValue(undefined);
    render(<Trash load={vi.fn().mockResolvedValueOnce([{ id: 'a', title: 'Original', deletedAt: 1, deletionStarted: false }]).mockResolvedValue([])} restore={restore} erase={vi.fn()} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Restore Original' }));
    await waitFor(() => expect(restore).toHaveBeenCalledWith('a'));
    expect(await screen.findByText('Meeting restored.')).toHaveAttribute('role', 'status');
  });
  it('requires explicit permanent confirmation before erasing', async () => {
    const erase = vi.fn().mockResolvedValue(undefined);
    render(<Trash load={vi.fn().mockResolvedValue([{ id: 'a', title: 'Original', deletedAt: 1, deletionStarted: false }])} restore={vi.fn()} erase={erase} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Permanently delete Original' }));
    expect(erase).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toHaveTextContent('cannot be undone');
    expect(screen.getByRole('dialog')).toHaveTextContent('audio, transcript, notes');
    fireEvent.click(screen.getByRole('button', { name: 'Delete permanently' }));
    await waitFor(() => expect(erase).toHaveBeenCalledWith('a'));
  });
  it('shows actual outbound state and host', async () => {
    render(<Privacy load={async () => [{ id: 'llm', what: 'Selected notes', when: 'only after your action', destinationHost: 'example.com', state: 'configured' }]} />);
    expect(await screen.findByText('example.com')).toBeVisible();
    expect(screen.getByText('only after your action')).toBeVisible();
  });
  it.each(['off', 'unknown'] as const)('warns for FileVault %s without blocking', (state) => {
    render(<FileVaultNotice state={state} />);
    expect(screen.getByRole('status')).toHaveTextContent(/FileVault/);
  });
  it('does not warn when FileVault is on', () => {
    render(<FileVaultNotice state="on" />);
    expect(screen.queryByRole('status')).toBeNull();
  });
  it('explains that upgrades keep old audio and changes never shorten existing deadlines', async () => {
    render(<Retention load={async () => ({ audio_retention: '7d' })} save={vi.fn()} />);
    expect(screen.getByText(/audio from before this upgrade/i)).toBeVisible();
    expect(screen.getByText(/never shorten/i)).toBeVisible();
    await waitFor(() => expect(screen.getByLabelText('Audio retention')).toBeEnabled());
  });
  it('contacts nothing until the user clicks, and installs only after a second click', async () => {
    const check = vi.fn(async () => '0.2.0'); const install = vi.fn(async () => undefined);
    render(<UpdateCheck check={check} install={install} />);
    expect(check).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Check for updates' }));
    expect(await screen.findByText('Version 0.2.0 is available.')).toBeVisible();
    expect(install).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Install and restart' }));
    await waitFor(() => expect(install).toHaveBeenCalledTimes(1));
    expect(check).toHaveBeenCalledTimes(1);
  });
  it('says up to date and reports a failed check', async () => {
    const { unmount } = render(<UpdateCheck check={async () => null} install={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Check for updates' }));
    expect(await screen.findByText('Interline is up to date.')).toBeVisible();
    unmount();
    render(<UpdateCheck check={async () => { throw new Error('offline'); }} install={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Check for updates' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('offline');
  });
  it('uses the globally defined ochre token without an inline color fallback', () => {
    render(<FileVaultNotice state="off" />);
    expect(screen.getByRole('status').getAttribute('style')).toContain('var(--ochre)');
    expect(screen.getByRole('status').getAttribute('style')).not.toContain('#');
  });

});
