import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CoreClient, type Block, type BlockPayload as ApiBlockPayload, type Provider } from '../coreClient';
import { openJournal, replay, submitEdit, type BlockPayload as JournalPayload, type Journal } from './journal';
import { AiAddition } from './AiAddition';
import { EnhancedToggle } from './EnhancedToggle';

export type ViewMode = 'mine' | 'enhanced';

interface Props {
  client: CoreClient;
  meetingId: string;
  coreAlive: boolean;
  highlightedSegmentId?: number;
  onCoreDown?: () => void;
}

function apiPayload(journal: JournalPayload): ApiBlockPayload {
  return {
    kind: journal.kind,
    position: journal.position,
    content: journal.content,
    plain_text: journal.plainText,
    anchor_ms: journal.anchorMs,
  };
}

function journalPayload(kind: string, position: string, content: string, plainText: string, anchorMs?: number): JournalPayload {
  return { kind, position, content, plainText, anchorMs };
}

export function Notes({ client, meetingId, coreAlive, highlightedSegmentId }: Props) {
  const [blocks, setBlocks] = useState<Block[]>([]);
  const [providers, setProviders] = useState<Provider[]>([]);
  const [mode, setMode] = useState<ViewMode>('enhanced');
  const [error, setError] = useState<string>();
  const [enhancing, setEnhancing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [highlightedSegment, setHighlightedSegment] = useState<number | undefined>(highlightedSegmentId);
  const [transcript, setTranscript] = useState<{ id: number; speaker: string; startMs: number; text: string }[]>([]);
  const journalRef = useRef<Journal | undefined>(undefined);
  const revsRef = useRef<Map<string, number>>(new Map());
  const transcriptRef = useRef<HTMLUListElement | null>(null);

  const refreshBlocks = useCallback(async () => {
    try {
      const [b, t, p] = await Promise.all([
        client.blocks(meetingId),
        client.transcript(meetingId),
        client.providers(),
      ]);
      setBlocks(b);
      setTranscript(t.map((s) => ({ id: s.id, speaker: s.speaker, startMs: s.startMs, text: s.text })));
      setProviders(p);
      for (const block of b) {
        revsRef.current.set(block.id, block.client_rev);
      }
    } finally {
      setLoading(false);
    }
  }, [client, meetingId]);

  useEffect(() => {
    let alive = true;
    const name = `interline-notes-${meetingId}`;
    openJournal(name).then((journal) => {
      if (!alive) return journal.close();
      journalRef.current = journal;
      void refreshBlocks();
    });
    return () => {
      alive = false;
      journalRef.current?.close();
      journalRef.current = undefined;
    };
  }, [meetingId, refreshBlocks]);

  useEffect(() => {
    if (!coreAlive || !journalRef.current) return;
    void replay(journalRef.current, async (entry) => {
      await client.putBlock(entry.meetingId, entry.blockId, apiPayload(entry.payload), entry.clientRev);
    }).then(() => refreshBlocks());
  }, [coreAlive, client, refreshBlocks]);

  const hasProvider = providers.some((p) => p.has_key);
  const userBlocks = blocks.filter((b) => b.origin === 'user');
  const aiBlocks = blocks.filter((b) => b.origin === 'ai');
  const additions = aiBlocks.filter((b) => b.parent_id);
  const otherPoints = aiBlocks.filter((b) => !b.parent_id);

  const sendBlock = async (blockId: string, payload: JournalPayload) => {
    const journal = journalRef.current;
    if (!journal) return;
    const nextRev = (revsRef.current.get(blockId) ?? 0) + 1;
    revsRef.current.set(blockId, nextRev);
    const ok = await submitEdit(journal, async () => {
      await client.putBlock(meetingId, blockId, apiPayload(payload), nextRev);
    }, { meetingId, blockId, clientRev: nextRev, payload });
    if (ok) {
      await refreshBlocks();
    } else if (coreAlive) {
      setError('Failed to save a note.');
    }
  };

  const enhance = async () => {
    if (!hasProvider) return;
    setEnhancing(true);
    setError(undefined);
    try {
      const result = await client.enhance(meetingId);
      if (result.status === 'invalid_output') {
        setError(result.message ?? 'The model did not respect the format');
      }
      await refreshBlocks();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setEnhancing(false);
    }
  };

  const acceptAddition = async (blockId: string) => {
    try {
      await client.acceptBlock(meetingId, blockId);
      await refreshBlocks();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const deleteAddition = async (blockId: string) => {
    try {
      await client.deleteBlock(meetingId, blockId);
      await refreshBlocks();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const onSelectSource = (segmentId: number) => {
    setHighlightedSegment(segmentId);
    setTimeout(() => setHighlightedSegment((current) => (current === segmentId ? undefined : current)), 3000);
  };

  useEffect(() => {
    if (highlightedSegment == null || !transcriptRef.current) return;
    const item = transcriptRef.current.querySelector(`[data-segment-id="${highlightedSegment}"]`);
    item?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [highlightedSegment]);

  if (loading) {
    return <p role="status">Loading notes...</p>;
  }

  return (
    <section className="notes-view">
      <div className="notes-toolbar">
        <EnhancedToggle mode={mode} onChange={setMode} />
        <button type="button" onClick={enhance} disabled={!hasProvider || enhancing}>
          Enhance
        </button>
        {!hasProvider && <span>Configure a provider</span>}
      </div>
      {error && <p role="alert">{error}</p>}
      {!coreAlive && <p role="status">Interline is restarting its engine</p>}
      <UserEditor blocks={userBlocks} onChange={sendBlock} />
      {mode === 'enhanced' && (
        <>
          {additions.map((block) => (
            <AiAddition
              key={block.id}
              blockId={block.id}
              text={block.plain_text}
              state={(block.ai_state as 'proposed' | 'accepted' | 'edited') ?? 'proposed'}
              sources={block.sources.map((id) => {
                const seg = transcript.find((s) => s.id === id);
                return { segmentId: id, label: seg ? `${seg.speaker} ${formatClock(seg.startMs)}` : `S${id}` };
              })}
              onSelectSource={onSelectSource}
              onAccept={() => acceptAddition(block.id)}
              onDelete={() => deleteAddition(block.id)}
            />
          ))}
          {otherPoints.length > 0 && (
            <div className="other-points">
              <h3>Other points</h3>
              {otherPoints.map((block) => (
                <AiAddition
                  key={block.id}
                  blockId={block.id}
                  text={block.plain_text}
                  state={(block.ai_state as 'proposed' | 'accepted' | 'edited') ?? 'proposed'}
                  sources={block.sources.map((id) => {
                    const seg = transcript.find((s) => s.id === id);
                    return { segmentId: id, label: seg ? `${seg.speaker} ${formatClock(seg.startMs)}` : `S${id}` };
                  })}
                  onSelectSource={onSelectSource}
                  onAccept={() => acceptAddition(block.id)}
                  onDelete={() => deleteAddition(block.id)}
                />
              ))}
            </div>
          )}
        </>
      )}
      <h2>Transcript</h2>
      <ul aria-label="Transcript" ref={transcriptRef}>
        {transcript.map((seg) => (
          <li
            key={seg.id}
            data-segment-id={seg.id}
            data-highlighted={highlightedSegment === seg.id}
          >
            <strong>{seg.speaker}</strong> {seg.text}
          </li>
        ))}
      </ul>
    </section>
  );
}

function formatClock(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

interface UserEditorProps {
  blocks: Block[];
  onChange: (blockId: string, payload: JournalPayload) => void;
}

function positionFor(index: number): string {
  return `a${String(index).padStart(2, '0')}`;
}

function uuid(): string {
  // UUID v7: 48-bit timestamp (ms since epoch), version 7, random, variant bits.
  const now = Date.now();
  const timeHex = now.toString(16).padStart(12, '0');
  return `${timeHex.slice(0, 8)}-${timeHex.slice(8)}-7${hex(3)}-${hex(4, true)}-${hex(12)}`;
}

function hex(nibbles: number, variant: boolean = false): string {
  let out = '';
  for (let i = 0; i < nibbles; i++) {
    const nibble = variant && i === 0 ? (Math.random() * 4 | 0) | 0x8 : Math.random() * 16 | 0;
    out += nibble.toString(16);
  }
  return out;
}

const SAVE_DEBOUNCE_MS = 300;

function UserEditor({ blocks, onChange }: UserEditorProps) {
  const editorRef = useRef<HTMLDivElement>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const initialHtml = useMemo(() => {
    if (blocks.length === 0) return '<div data-block-id="' + uuid() + '" data-origin="user"><br></div>';
    return blocks.map((b) => `<div data-block-id="${b.id}" data-origin="user">${escapeHtml(b.plain_text)}</div>`).join('');
  }, []);

  const save = () => {
    const editor = editorRef.current;
    if (!editor) return;
    const children = Array.from(editor.children).filter(
      (c): c is HTMLElement => c instanceof HTMLElement && c.tagName !== 'BR'
    );
    const seen = new Set<string>();
    children.forEach((child, index) => {
      const existing = blocks[index];
      let id = child.dataset.blockId ?? existing?.id;
      if (!id || seen.has(id)) id = uuid();
      seen.add(id);
      const text = child.innerText ?? '';
      onChange(id, journalPayload('paragraph', existing?.position ?? positionFor(index), text, text, existing?.anchor_ms ?? 0));
    });
  };

  const handleInput = () => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(save, SAVE_DEBOUNCE_MS);
  };

  const handleBlur = () => {
    if (timerRef.current) clearTimeout(timerRef.current);
    save();
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      document.execCommand('insertParagraph', false);
    }
  };

  return (
    <div
      ref={editorRef}
      role="textbox"
      aria-label="Notes"
      aria-multiline="true"
      contentEditable
      className="notes-editor"
      onInput={handleInput}
      onBlur={handleBlur}
      onKeyDown={handleKeyDown}
      dangerouslySetInnerHTML={{ __html: initialHtml }}
    />
  );
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
