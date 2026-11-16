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
    return `http://127.0.0.1:${this.endpoint.port}/api/v1${path}`;
  }

  private headers(): Record<string, string> {
    return { Authorization: `Bearer ${this.endpoint.token}` };
  }

  private async json<T>(path: string, init?: RequestInit): Promise<T> {
    const signal = init?.signal ?? AbortSignal.timeout(REQUEST_TIMEOUT_MS);
    const response = await fetch(this.url(path), { ...init, signal, headers: { ...this.headers(), ...init?.headers } });
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({}))) as { detail?: string; code?: string };
      throw Object.assign(new Error(problem.detail ?? `HTTP ${response.status}`), { code: problem.code });
    }
    return (await response.json()) as T;
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
