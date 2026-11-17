/** IndexedDB journal: every edit is written locally before it is sent, dropped on ack, replayed in order after reconnect. */

const DB_VERSION = 1;
const STORE = 'entries';

export interface BlockPayload {
  kind: string;
  position: string;
  content: string;
  plainText: string;
  anchorMs?: number;
}

export interface NewEntry {
  meetingId: string;
  blockId: string;
  clientRev: number;
  payload: BlockPayload;
}

export interface JournalEntry extends NewEntry {
  seq: number;
}

export interface Journal {
  record(entry: NewEntry): Promise<JournalEntry>;
  pending(meetingId?: string): Promise<JournalEntry[]>;
  ack(meetingId: string, blockId: string, clientRev: number): Promise<void>;
  replay(send: (entry: JournalEntry) => Promise<void>): Promise<void>;
  close(): void;
}

interface StoredEntry {
  seq: number;
  meetingId: string;
  blockId: string;
  clientRev: number;
  payload: BlockPayload;
}

export async function openJournal(name: string): Promise<Journal> {
  const db = await openDB(name);
  const max = await maxSeq(db);
  let nextSeq = max + 1;

  return {
    async record(entry) {
      const stored: StoredEntry = { seq: nextSeq++, ...entry };
      await putEntry(db, stored);
      return stored;
    },

    async pending(meetingId?: string) {
      const entries = await allEntries(db);
      const filtered = meetingId ? entries.filter((e) => e.meetingId === meetingId) : entries;
      return filtered.sort(bySeq);
    },

    async ack(meetingId, blockId, clientRev) {
      const entries = await allEntries(db);
      const toDelete = entries.filter(
        (e) => e.meetingId === meetingId && e.blockId === blockId && e.clientRev <= clientRev
      );
      await deleteEntries(db, toDelete);
    },

    async replay(send) {
      const entries = await allEntries(db);
      entries.sort(bySeq);
      let failed = false;
      for (const entry of entries) {
        if (failed) break;
        try {
          await send(entry);
          await deleteEntry(db, entry);
        } catch {
          failed = true;
        }
      }
    },

    close() {
      db.close();
    },
  };
}

/** Convenience: record, send, and ack in one call. Returns true when the core acknowledged. */
export async function replay(
  journal: Journal,
  send: (entry: JournalEntry) => Promise<void>
): Promise<void> {
  await journal.replay(send);
}

export async function submitEdit(
  journal: Journal,
  send: (entry: JournalEntry) => Promise<void>,
  entry: NewEntry
): Promise<boolean> {
  const recorded = await journal.record(entry);
  try {
    await send(recorded);
    await journal.ack(entry.meetingId, entry.blockId, entry.clientRev);
    return true;
  } catch {
    return false;
  }
}

function openDB(name: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(name, DB_VERSION);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        const store = db.createObjectStore(STORE, { keyPath: 'seq' });
        store.createIndex('meeting', ['meetingId', 'blockId', 'clientRev'], { unique: false });
      }
    };
  });
}

function maxSeq(db: IDBDatabase): Promise<number> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, 'readonly');
    const store = tx.objectStore(STORE);
    const request = store.openCursor(null, 'prev');
    request.onerror = () => reject(request.error);
    request.onsuccess = () => {
      const cursor = request.result;
      resolve(cursor ? (cursor.value as StoredEntry).seq : 0);
    };
  });
}

function allEntries(db: IDBDatabase): Promise<StoredEntry[]> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, 'readonly');
    const store = tx.objectStore(STORE);
    const request = store.getAll();
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result as StoredEntry[]);
  });
}

function putEntry(db: IDBDatabase, entry: StoredEntry): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite');
    const request = tx.objectStore(STORE).put(entry);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve();
  });
}

function deleteEntry(db: IDBDatabase, entry: StoredEntry): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite');
    const request = tx.objectStore(STORE).delete(entry.seq);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve();
  });
}

function deleteEntries(db: IDBDatabase, entries: StoredEntry[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite');
    const store = tx.objectStore(STORE);
    entries.forEach((e) => store.delete(e.seq));
    tx.onerror = () => reject(tx.error);
    tx.oncomplete = () => resolve();
  });
}

function bySeq(a: StoredEntry, b: StoredEntry): number {
  return a.seq - b.seq;
}
