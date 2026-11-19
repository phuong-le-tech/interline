// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Today } from './Today';
import type { CoreClient } from './coreClient';

afterEach(cleanup);

const event = { id: 'e1', title: 'Standup', starts_at: Date.now(), ends_at: Date.now() + 1, attendees: [] };

function fake(over: Record<string, unknown> = {}) {
  return {
    icsSource: vi.fn().mockResolvedValue({ configured: false }),
    calendarEvents: vi.fn().mockResolvedValue([]),
    setIcsSource: vi.fn().mockResolvedValue(undefined),
    syncCalendar: vi.fn().mockResolvedValue({}),
    meetingFromEvent: vi.fn().mockResolvedValue({ id: 'm1' }),
    createMeeting: vi.fn().mockResolvedValue({ id: 'm2' }),
    removeIcsSource: vi.fn().mockResolvedValue(undefined),
    ...over,
  };
}

describe('Today', () => {
  it('fetches nothing until the user connects, then syncs once and never shows the address back', async () => {
    const core = fake();
    render(<Today client={core as unknown as CoreClient} onOpenMeeting={() => undefined} />);
    await waitFor(() => expect(core.icsSource).toHaveBeenCalled());
    expect(core.syncCalendar).not.toHaveBeenCalled();

    core.icsSource.mockResolvedValue({ configured: true, host: 'cal.example.com' });
    core.calendarEvents.mockResolvedValue([event]);
    fireEvent.change(screen.getByLabelText('Calendar address (ICS)'), { target: { value: 'https://cal.example.com/secret.ics' } });
    fireEvent.click(screen.getByRole('button', { name: 'Connect calendar' }));

    expect(await screen.findByText('Standup', { exact: false })).toBeTruthy();
    expect(core.setIcsSource).toHaveBeenCalledWith('https://cal.example.com/secret.ics');
    expect(core.syncCalendar).toHaveBeenCalledTimes(1);
    expect(document.body.textContent).not.toContain('secret.ics');
  });

  it('opens a meeting from an event and from a typed title', async () => {
    const core = fake({ icsSource: vi.fn().mockResolvedValue({ configured: true, host: 'h' }), calendarEvents: vi.fn().mockResolvedValue([event]) });
    const open = vi.fn();
    render(<Today client={core as unknown as CoreClient} onOpenMeeting={open} />);

    fireEvent.click(await screen.findByRole('button', { name: 'Take notes' }));
    await waitFor(() => expect(open).toHaveBeenCalledWith('m1'));

    fireEvent.change(screen.getByLabelText('Meeting title'), { target: { value: 'Sync' } });
    fireEvent.click(screen.getByRole('button', { name: 'New meeting' }));
    await waitFor(() => expect(open).toHaveBeenCalledWith('m2'));
    expect(core.createMeeting).toHaveBeenCalledWith('Sync');
  });
});
