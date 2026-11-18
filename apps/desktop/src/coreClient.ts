import { invoke } from '@tauri-apps/api/core';

export type Endpoint = { port: number; token: string };

export type Meeting = { id: string; title: string; status: string; startedAt: number | null; endedAt: number | null };

export type TranscriptLine = {
  meetingId: string;
  stream: string;
  seg: string;
  speaker: string;
  startMs: number;
  endMs: number;
  text: string;
};

export type CoreEvent = { name: string; data: Record<string, unknown> };

export type ModelState = 'absent' | 'partial' | 'downloading' | 'installed' | 'failed';

export type ModelView = {
  id: string;
  name: string;
  kind: string;
  sizeBytes: number;
  state: ModelState;
  downloadedBytes: number;
  error?: string;
};

export type Block = {
  id: string;
  kind: string;
  origin: 'user' | 'ai';
  parent_id?: string;
  position: string;
  content: string;
  plain_text: string;
  anchor_ms?: number;
  client_rev: number;
  ai_state?: 'proposed' | 'accepted' | 'edited';
  sources: number[];
};

export type BlockPayload = {
  kind: string;
  position: string;
  content: string;
  plain_text: string;
  anchor_ms?: number;
};

export type Provider = {
  id: string;
  kind: string;
  name: string;
  base_url: string;
  model: string;
  supports_json_schema: boolean;
  is_default: boolean;
  has_key: boolean;
  context_tokens?: number;
  price_in_per_mtok?: number;
  price_out_per_mtok?: number;
};

export type Template = { id: string; name: string; instructions: string; builtin: boolean };

export type SearchHit = { type: 'block' | 'segment'; meeting_id: string; id: string; snippet: string };

export type ActionItem = { id: string; text: string; owner?: string; due_date?: string; status: string; source_segment_id?: number };

export type AiRun = {
  id: string;
  kind: string;
  provider_id?: string;
  model: string;
  prompt_version: string;
  input_tokens?: number;
  output_tokens?: number;
  status: string;
  cost_estimate_usd?: number;
};

const ENDPOINT_POLL_MS = 200;
const RECONNECT_MS = 1000;
const REQUEST_TIMEOUT_MS = 30000;

export function inTauri(): boolean {
  return '__TAURI_INTERNALS__' in window;
}

async function readEndpoint(): Promise<Endpoint | undefined> {
  if (inTauri()) {
    try {
      return await invoke<Endpoint>('get_core_endpoint');
    } catch {
      return undefined; // the shell has not seen READY yet
    }
  }
  // Browser development only: Vite exposes the values of a core started by the Playwright helper.
  if (import.meta.env.DEV) {
    const port = Number(import.meta.env.VITE_CORE_PORT);
    const token = import.meta.env.VITE_CORE_TOKEN;
    if (Number.isInteger(port) && port > 0 && token) return { port, token };
  }
  return undefined;
}

/** Poll until the shell (or the dev environment) can tell where the core listens. */
export async function waitForEndpoint(signal: AbortSignal): Promise<Endpoint | undefined> {
  while (!signal.aborted) {
    const endpoint = await readEndpoint();
    if (endpoint) return endpoint;
    await new Promise((done) => setTimeout(done, ENDPOINT_POLL_MS));
  }
  return undefined;
}

export class CoreClient {
  constructor(private readonly endpoint: Endpoint) {}

  private url(path: string): string {
    const allowed =
        /^\/(recording\/(start|stop)|meetings|meetings\/[A-Za-z0-9%-._~]+\/(transcript|blocks|blocks\/[A-Za-z0-9%-._~]+|blocks\/[A-Za-z0-9%-._~]+\/accept|enhance|enhance\/undo|followup-email|actions|ai-runs|export)|search(\?.*)?|templates|providers(\/[A-Za-z0-9%-._~]+(\/(key|test))?)?|models(\/[A-Za-z0-9%._~-]+\/download)?|events)$/;
    if (!allowed.test(path)) {
      throw new Error(`invalid API path: ${path}`);
    }
    return `http://127.0.0.1:${this.endpoint.port}/api/v1${path}`;
  }

  private headers(): Record<string, string> {
    return { Authorization: `Bearer ${this.endpoint.token}` };
  }

  private async request(path: string, init?: RequestInit): Promise<Response> {
    return fetch(this.url(path), {
      ...init,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      headers: { ...this.headers(), ...init?.headers },
    });
  }

  private async json<T>(path: string, init?: RequestInit): Promise<T> {
    const response = await this.request(path, init);
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({}))) as { detail?: string; code?: string };
      throw Object.assign(new Error(problem.detail ?? `HTTP ${response.status}`), { code: problem.code });
    }
    return (await response.json()) as T;
  }

  private async empty(path: string, init?: RequestInit): Promise<void> {
    const response = await this.request(path, init);
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({}))) as { detail?: string; code?: string };
      throw Object.assign(new Error(problem.detail ?? `HTTP ${response.status}`), { code: problem.code });
    }
  }

  startRecording(): Promise<{ meetingId: string }> {
    return this.json('/recording/start', { method: 'POST' });
  }

  stopRecording(): Promise<unknown> {
    return this.json('/recording/stop', { method: 'POST' });
  }

  meetings(): Promise<Meeting[]> {
    return this.json('/meetings');
  }

  transcript(meetingId: string): Promise<{ id: number; stream: string; seg: string; speaker: string; startMs: number; endMs: number; text: string; suppressed?: boolean }[]> {
    return this.json(`/meetings/${encodeURIComponent(meetingId)}/transcript`);
  }

  blocks(meetingId: string): Promise<Block[]> {
    return this.json(`/meetings/${encodeURIComponent(meetingId)}/blocks`);
  }

  putBlock(meetingId: string, blockId: string, payload: BlockPayload, clientRev: number): Promise<{ id: string; client_rev: number; applied: boolean }> {
    return this.json(`/meetings/${encodeURIComponent(meetingId)}/blocks/${encodeURIComponent(blockId)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...payload, client_rev: clientRev }),
    });
  }

  deleteBlock(meetingId: string, blockId: string): Promise<void> {
    return this.empty(`/meetings/${encodeURIComponent(meetingId)}/blocks/${encodeURIComponent(blockId)}`, { method: 'DELETE' });
  }

  acceptBlock(meetingId: string, blockId: string): Promise<void> {
    return this.empty(`/meetings/${encodeURIComponent(meetingId)}/blocks/${encodeURIComponent(blockId)}/accept`, { method: 'POST' });
  }

  enhance(meetingId: string, templateId?: string): Promise<{ run_id: string; status: string; message?: string }> {
    return this.json(`/meetings/${encodeURIComponent(meetingId)}/enhance`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(templateId ? { template_id: templateId } : {}),
    });
  }

  undoEnhance(meetingId: string): Promise<void> {
    return this.empty(`/meetings/${encodeURIComponent(meetingId)}/enhance/undo`, { method: 'POST' });
  }

  followUpEmail(meetingId: string): Promise<{ run_id: string; status: string; subject?: string; body?: string; sources: number[]; message?: string }> {
    return this.json(`/meetings/${encodeURIComponent(meetingId)}/followup-email`, { method: 'POST' });
  }

  actions(meetingId: string): Promise<ActionItem[]> {
    return this.json(`/meetings/${encodeURIComponent(meetingId)}/actions`);
  }

  aiRuns(meetingId: string): Promise<AiRun[]> {
    return this.json(`/meetings/${encodeURIComponent(meetingId)}/ai-runs`);
  }

  exportMarkdown(meetingId: string, withTranscript: boolean): Promise<string> {
    return this.text(`/meetings/${encodeURIComponent(meetingId)}/export?format=markdown&transcript=${withTranscript}`);
  }

  private async text(path: string): Promise<string> {
    const response = await this.request(path);
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({}))) as { detail?: string; code?: string };
      throw Object.assign(new Error(problem.detail ?? `HTTP ${response.status}`), { code: problem.code });
    }
    return response.text();
  }

  search(query: string): Promise<{ results: SearchHit[] }> {
    return this.json(`/search?q=${encodeURIComponent(query)}`);
  }

  templates(): Promise<Template[]> {
    return this.json('/templates');
  }

  providers(): Promise<Provider[]> {
    return this.json('/providers');
  }

  provider(id: string): Promise<Provider> {
    return this.json(`/providers/${encodeURIComponent(id)}`);
  }

  createProvider(body: Omit<Provider, 'id' | 'has_key'>): Promise<Provider> {
    return this.json('/providers', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  }

  updateProvider(id: string, body: Omit<Provider, 'id' | 'has_key'>): Promise<Provider> {
    return this.json(`/providers/${encodeURIComponent(id)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  }

  deleteProvider(id: string): Promise<void> {
    return this.empty(`/providers/${encodeURIComponent(id)}`, { method: 'DELETE' });
  }

  saveProviderKey(id: string, key: string): Promise<void> {
    return this.empty(`/providers/${encodeURIComponent(id)}/key`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ key }) });
  }

  removeProviderKey(id: string): Promise<void> {
    return this.empty(`/providers/${encodeURIComponent(id)}/key`, { method: 'DELETE' });
  }

  testProvider(id: string): Promise<{ status: string; detail: string }> {
    return this.json(`/providers/${encodeURIComponent(id)}/test`, { method: 'POST' });
  }

  models(): Promise<ModelView[]> {
    return this.json('/models');
  }

  /** The only call that makes the core fetch a model; nothing else downloads anything. */
  downloadModel(id: string): Promise<unknown> {
    return this.json(`/models/${encodeURIComponent(id)}/download`, { method: 'POST' });
  }

  /** EventSource cannot send an Authorization header, so the stream is read with fetch. */
  async subscribe(onEvent: (event: CoreEvent) => void, signal: AbortSignal): Promise<void> {
    while (!signal.aborted) {
      try {
        const response = await fetch(this.url('/events'), {
          headers: { ...this.headers(), Accept: 'text/event-stream' },
          signal,
        });
        if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);
        await readEvents(response.body, onEvent);
      } catch {
        if (signal.aborted) return;
      }
      await new Promise((done) => setTimeout(done, RECONNECT_MS));
    }
  }
}

async function readEvents(body: ReadableStream<Uint8Array>, onEvent: (event: CoreEvent) => void): Promise<void> {
  const reader = body.pipeThrough(new TextDecoderStream()).getReader();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) return;
    buffer += value;
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const event = parseEvent(buffer.slice(0, boundary));
      if (event) onEvent(event);
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
  }
}

function parseEvent(block: string): CoreEvent | undefined {
  let name = 'message';
  const data: string[] = [];
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  if (data.length === 0) return undefined; // comment-only block, such as the initial keep-alive
  try {
    return { name, data: JSON.parse(data.join('\n')) as Record<string, unknown> };
  } catch {
    return undefined;
  }
}
