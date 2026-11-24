// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Consent } from './Consent';

const EN = 'I would like to record this meeting to help with my notes. Is everyone comfortable with that?';
const FR = 'Je souhaite enregistrer cette réunion pour ma prise de notes. Est-ce que tout le monde est d’accord ?';

beforeEach(() => {
  // jsdom has no native modal implementation; keyboard trapping remains the component's responsibility.
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function open(locale = 'en') {
  const onContinue = vi.fn().mockResolvedValue(undefined);
  const onCancel = vi.fn();
  render(<Consent locale={locale} onContinue={onContinue} onCancel={onCancel} />);
  return { onContinue, onCancel };
}

describe('Consent reminder', () => {
  it.each([['en', EN], ['fr-FR', FR]])('copies the neutral %s sentence without continuing', async (locale, sentence) => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const { onContinue } = open(locale);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAccessibleName(/consent|record|enregistr/i);
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByText(sentence)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /copy|copier/i }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(sentence));
    expect(onContinue).not.toHaveBeenCalled();
  });

  it('Cancel calls only cancellation even after selecting the opt out', () => {
    const { onContinue, onCancel } = open();
    fireEvent.click(screen.getByRole('checkbox', { name: /do not remind me again/i }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onContinue).not.toHaveBeenCalled();
  });

  it.each([false, true])('Continue passes doNotRemind=%s exactly once', async (checked) => {
    const { onContinue, onCancel } = open();
    if (checked) fireEvent.click(screen.getByRole('checkbox', { name: /do not remind me again/i }));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(onContinue).toHaveBeenCalledExactlyOnceWith(checked));
    expect(onCancel).not.toHaveBeenCalled();
  });

  it('traps Tab in both directions and Escape cancels without acknowledging', () => {
    const { onContinue, onCancel } = open();
    const dialog = screen.getByRole('dialog');
    expect(dialog.contains(document.activeElement)).toBe(true);
    const controls = [...dialog.querySelectorAll<HTMLElement>('button, input, [tabindex="0"]')];
    const first = controls[0];
    const last = controls[controls.length - 1];
    last.focus();
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(first).toHaveFocus();
    first.focus();
    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();
    fireEvent.keyDown(last, { key: 'Escape' });
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onContinue).not.toHaveBeenCalled();
  });

  it('clipboard failure stays in the dialog and leaves Continue usable', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('Clipboard unavailable'));
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
    const { onContinue } = open();
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await waitFor(() => expect(writeText).toHaveBeenCalled());
    expect(screen.getByRole('dialog')).toBeVisible();
    expect(onContinue).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(onContinue).toHaveBeenCalledWith(false));
  });
});
