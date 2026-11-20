// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CoreClient } from '../coreClient';
import { Brief } from './Brief';

afterEach(cleanup);

const lines = [{ text: 'Confirm the budget decision.', sources: ['previous'] }];
const raw = {
  meetingId: 'next',
  participants: [{ id: 'alice', name: 'Alice', email: null }],
  related: [{ meetingId: 'previous', title: 'Previous budget review', date: 1, matchedBy: 'title', notes: ['My original words'] }],
  openActions: [{ id: 'owed', meetingId: 'previous', text: 'Send forecast', ownerLabel: null, dueDate: null }],
};

function fake(ai = { status: 'missing', lines: [] as typeof lines }, provider = true) {
  return {
    brief: vi.fn().mockResolvedValue({ ...raw, ai }),
    providers: vi.fn().mockResolvedValue(provider ? [{ id: 'stub', kind: 'openai_compatible', is_default: true, has_key: true }] : []),
    writeBrief: vi.fn().mockResolvedValue({ status: 'ok', lines }),
  };
}

async function open(core: ReturnType<typeof fake>) {
  render(<Brief client={core as unknown as CoreClient} meetingId="next" />);
  fireEvent.click(await screen.findByText(/^Brief:/));
  return screen.getByLabelText('Brief');
}

describe('AI pre-meeting brief', () => {
  it('disables Write brief without a provider and explains how to enable it', async () => {
    const core = fake(undefined, false);
    const brief = await open(core);
    const button = within(brief).getByRole('button', { name: 'Write brief' });
    expect(button).toBeDisabled();
    expect(brief).toHaveTextContent('Configure a provider');
    fireEvent.click(button);
    expect(core.writeBrief).not.toHaveBeenCalled();
    expect(within(brief).getByText('My original words')).toBeVisible();
  });

  it('renders cached text above the raw list with a citation chip and no generation on mount', async () => {
    const core = fake({ status: 'ok', lines });
    const brief = await open(core);
    const text = within(brief).getByText(lines[0].text);
    const citation = within(brief).getByRole('button', { name: 'Source: Previous budget review' });
    expect(citation).toBeVisible();
    expect(text.compareDocumentPosition(within(brief).getByRole('list', { name: 'Related meetings' }))
      & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(core.writeBrief).not.toHaveBeenCalled();
    expect(within(brief).getByText('My original words')).toBeVisible();
  });

  it('shows a stale hint, keeps the raw fallback, and refreshes only on click', async () => {
    const core = fake({ status: 'stale', lines: [] });
    const brief = await open(core);
    expect(within(brief).getByText(/stale/i)).toBeVisible();
    expect(within(brief).queryByText(lines[0].text)).toBeNull();
    expect(within(brief).getByText('Send forecast')).toBeVisible();
    expect(core.writeBrief).not.toHaveBeenCalled();
    core.brief.mockResolvedValue({ ...raw, ai: { status: 'ok', lines } });
    fireEvent.click(within(brief).getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(core.writeBrief).toHaveBeenCalledExactlyOnceWith('next'));
    expect(await within(brief).findByText(lines[0].text)).toBeVisible();
  });

  it('writes on explicit request with a provider and keeps user excerpts intact', async () => {
    const core = fake();
    const brief = await open(core);
    core.brief.mockResolvedValue({ ...raw, ai: { status: 'ok', lines } });
    fireEvent.click(within(brief).getByRole('button', { name: 'Write brief' }));
    await waitFor(() => expect(core.writeBrief).toHaveBeenCalledExactlyOnceWith('next'));
    expect(await within(brief).findByText(lines[0].text)).toBeVisible();
    expect(within(brief).getByText('My original words')).toBeVisible();
  });
});
