/* eslint-disable @typescript-eslint/no-unused-vars -- skeleton: parameters are used once the code-writer implements it */
// M2 skeleton: the IndexedDB edit journal. Behaviour is specified by journal.test.ts (AC2).

export type BlockPayload = { kind: string; position: string; content: string; plainText: string; anchorMs: number | null };

export type JournalEntry = { seq: number; meetingId: string; blockId: string; clientRev: number; payload: BlockPayload };

export type NewEntry = Omit<JournalEntry, 'seq'>;

/** Sends one edit to the core; resolves when the core acknowledged it. */
export type Sender = (entry: JournalEntry) => Promise<void>;

export interface Journal {
  /** Append an entry and return it with its sequence number (strictly increasing, also across reopen). */
  record(entry: NewEntry): Promise<JournalEntry>;
  /** Entries not yet acknowledged, in recorded order. */
  pending(meetingId?: string): Promise<JournalEntry[]>;
  /** Drop the entries of this block whose rev is at most {@code clientRev}. */
  ack(meetingId: string, blockId: string, clientRev: number): Promise<void>;
  close(): void;
}

export async function openJournal(_name = 'interline-journal'): Promise<Journal> {
  throw new Error('not implemented');
}

/** Journal first, then send; drop the entry on ack. Resolves true when acknowledged, false when kept for replay. */
export async function submitEdit(_journal: Journal, _send: Sender, _entry: NewEntry): Promise<boolean> {
  throw new Error('not implemented');
}

/** Send every pending entry in recorded order, acking each; stop at the first failure and keep the rest. */
export async function replay(_journal: Journal, _send: Sender): Promise<void> {
  throw new Error('not implemented');
}
