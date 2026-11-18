import 'fake-indexeddb/auto';
import { describe, expect, it, vi } from 'vitest';
import { openJournal, replay, submitEdit, type Journal, type JournalEntry, type NewEntry } from './journal';

/** AC2 (unit part): the IndexedDB journal writes before sending, drops on ack and replays in order. */

const payload = (text: string) => ({ kind: 'paragraph', position: 'a0', content: text, plainText: text, anchorMs: 1000 });
const entry = (blockId: string, clientRev: number, text = `${blockId} v${clientRev}`, meetingId = 'm1'): NewEntry =>
  ({ meetingId, blockId, clientRev, payload: payload(text) });
const fresh = () => openJournal(`journal-${crypto.randomUUID()}`);
const ids = (entries: JournalEntry[]) => entries.map((e) => `${e.blockId}@${e.clientRev}`);

describe('AC2_journalOrder', () => {
  it('writes the entry to the journal before it is sent', async () => {
    const journal = await fresh();
    let pendingWhileSending: string[] = [];
    const send = vi.fn(async () => {
      pendingWhileSending = ids(await journal.pending());
    });

    await submitEdit(journal, send, entry('a', 1));

    expect(send).toHaveBeenCalledTimes(1);
    expect(pendingWhileSending).toEqual(['a@1']);
  });

  it('drops the entry once the core acknowledged it', async () => {
    const journal = await fresh();

    const acknowledged = await submitEdit(journal, async () => undefined, entry('a', 1));

    expect(acknowledged).toBe(true);
    expect(await journal.pending()).toEqual([]);
  });

  it('keeps the entry when the send fails, and reports that it was kept', async () => {
    const journal = await fresh();

    const acknowledged = await submitEdit(journal, async () => { throw new Error('core down'); }, entry('a', 1, 'typed offline'));

    expect(acknowledged).toBe(false);
    const pending = await journal.pending();
    expect(ids(pending)).toEqual(['a@1']);
    expect(pending[0].payload.plainText).toBe('typed offline');
  });

  it('replays pending entries in the order they were recorded, across blocks', async () => {
    const journal = await fresh();
    await journal.record(entry('a', 1));
    await journal.record(entry('b', 1));
    await journal.record(entry('a', 2));
    const sent: string[] = [];

    await replay(journal, async (e) => { sent.push(`${e.blockId}@${e.clientRev}`); });

    expect(sent).toEqual(['a@1', 'b@1', 'a@2']);
    expect(await journal.pending()).toEqual([]);
  });

  it('stops replaying at the first failure and keeps that entry and the ones after it', async () => {
    const journal = await fresh();
    await journal.record(entry('a', 1));
    await journal.record(entry('b', 1));
    await journal.record(entry('a', 2));
    const attempts: string[] = [];

    await replay(journal, async (e) => {
      attempts.push(`${e.blockId}@${e.clientRev}`);
      if (e.blockId === 'b') throw new Error('core down again');
    });

    expect(attempts).toEqual(['a@1', 'b@1']);
    expect(ids(await journal.pending())).toEqual(['b@1', 'a@2']);
  });

  it('replays nothing and resolves when the journal is empty', async () => {
    const journal = await fresh();
    const send = vi.fn(async () => undefined);

    await replay(journal, send);

    expect(send).not.toHaveBeenCalled();
  });

  it('keeps its entries when it is closed and opened again, as after an app restart', async () => {
    const name = `journal-${crypto.randomUUID()}`;
    const first = await openJournal(name);
    await first.record(entry('a', 1, 'survives'));
    first.close();

    const reopened = await openJournal(name);

    const pending = await reopened.pending();
    expect(ids(pending)).toEqual(['a@1']);
    expect(pending[0].payload.plainText).toBe('survives');
  });

  it('numbers entries strictly increasing, also after reopening', async () => {
    const name = `journal-${crypto.randomUUID()}`;
    const first = await openJournal(name);
    const one = await first.record(entry('a', 1));
    first.close();
    const reopened = await openJournal(name);

    const two = await reopened.record(entry('a', 2));

    expect(two.seq).toBeGreaterThan(one.seq);
  });

  it('acknowledging a rev drops that block\'s entries up to the rev and nothing else', async () => {
    const journal = await fresh();
    for (const e of [entry('a', 1), entry('a', 2), entry('a', 3), entry('b', 1)]) await journal.record(e);

    await journal.ack('m1', 'a', 2);

    expect(ids(await journal.pending())).toEqual(['a@3', 'b@1']);
  });

  it('lists pending entries per meeting when asked', async () => {
    const journal: Journal = await fresh();
    await journal.record(entry('a', 1, 'x', 'm1'));
    await journal.record(entry('a', 1, 'y', 'm2'));

    expect((await journal.pending('m2')).map((e) => e.payload.plainText)).toEqual(['y']);
    expect(await journal.pending()).toHaveLength(2);
  });
});
