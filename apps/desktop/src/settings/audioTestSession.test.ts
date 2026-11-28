import { expect, it, vi } from 'vitest';
import { CoreClient, type Meeting } from '../coreClient';
import { AudioTestSession } from './audioTestSession';

function fixture() {
  const original: Meeting = { id: 'existing', title: 'My meeting', status: 'done', startedAt: 1, endedAt: 2, consentAckAt: 1 };
  let meetings = [original];
  const client = new CoreClient({ port: 1, token: 'sk-test-audio' });
  vi.spyOn(client, 'createMeeting').mockImplementation(async () => {
    meetings.push({ ...original, id: 'test', title: 'Audio test', status: 'draft' });
    return { id: 'test' };
  });
  vi.spyOn(client, 'acknowledgeConsent').mockResolvedValue();
  vi.spyOn(client, 'startRecording').mockImplementation(async () => { meetings[1].status = 'recording'; return { meetingId: 'test' }; });
  vi.spyOn(client, 'meetings').mockImplementation(async () => meetings);
  vi.spyOn(client, 'stopRecording').mockImplementation(async () => { meetings[1].status = 'done'; return {}; });
  vi.spyOn(client, 'deleteMeeting').mockResolvedValue();
  vi.spyOn(client, 'eraseMeeting').mockImplementation(async (id) => { meetings = meetings.filter((meeting) => meeting.id !== id); });
  return { client, original, session: new AudioTestSession(client) };
}

it('stops, trashes and erases only the temporary audio meeting, leaving the list unchanged', async () => {
  const { client, original, session } = fixture();
  await session.start('Audio test');
  await session.dispose();
  expect(await client.meetings()).toEqual([original]);
  expect(client.acknowledgeConsent).toHaveBeenCalledExactlyOnceWith('test');
  expect(client.deleteMeeting).toHaveBeenCalledExactlyOnceWith('test');
  expect(client.eraseMeeting).toHaveBeenCalledExactlyOnceWith('test');
  expect(client.stopRecording).toHaveBeenCalledOnce();
  expect(vi.mocked(client.deleteMeeting).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(client.eraseMeeting).mock.invocationCallOrder[0]);
});

it('waits for an in-flight start when closing and coalesces concurrent disposal', async () => {
  const { client, original, session } = fixture();
  const create = vi.mocked(client.createMeeting).getMockImplementation()!;
  let release!: () => void;
  vi.mocked(client.createMeeting).mockImplementation(async () => { await new Promise<void>((resolve) => { release = resolve; }); return create('Audio test'); });
  const starting = session.start('Audio test');
  const first = session.dispose();
  const second = session.dispose();
  release(); await starting; await Promise.all([first, second]);
  expect(await client.meetings()).toEqual([original]);
  expect(client.eraseMeeting).toHaveBeenCalledOnce();
});

it('also removes a temporary meeting whose recording failed to start', async () => {
  const { client, original, session } = fixture();
  vi.mocked(client.startRecording).mockRejectedValue(new Error('Unavailable'));
  await expect(session.start('Audio test')).rejects.toThrow('Unavailable');
  await session.dispose();
  expect(await client.meetings()).toEqual([original]);
  expect(client.stopRecording).not.toHaveBeenCalled();
});

it('permits a fresh test after meeting creation failed without creating anything', async () => {
  const { client, original, session } = fixture();
  vi.mocked(client.createMeeting).mockRejectedValueOnce(new Error('Offline'));
  await expect(session.start('Audio test')).rejects.toThrow('Offline');
  await session.dispose();
  await session.start('Audio test'); await session.dispose();
  expect(client.createMeeting).toHaveBeenCalledTimes(2);
  expect(await client.meetings()).toEqual([original]);
});
