import { test as base, expect } from '@playwright/test';
import { createServer, request as httpRequest, type IncomingMessage, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { DatabaseSync } from 'node:sqlite';
import { mkdtemp, mkdir, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import {
  desktop, launch, origin, readyPort, repository, stopProcess, token, type ManagedProcess,
} from './recording-stack';

/** One golden meeting as written under fixtures/golden/meeting-N/. */
export type Golden = {
  title: string;
  language: string;
  t0_wall_ms: number;
  segments: { id: number; stream: string; speaker: string; start_ms: number; end_ms: number; text: string }[];
  notes: { id: string; kind: string; anchor_ms: number; text: string }[];
};

export async function loadGolden(n: number): Promise<{ meeting: Golden; reply: string }> {
  const dir = join(repository, `fixtures/golden/meeting-${n}`);
  return {
    meeting: JSON.parse(await readFile(join(dir, 'meeting.json'), 'utf8')) as Golden,
    reply: await readFile(join(dir, 'stub-reply.json'), 'utf8'),
  };
}

/** Loopback OpenAI-compatible endpoint standing in for a model. Records every request. */
export class FakeModel {
  readonly requests: { method: string; path: string; body: string }[] = [];
  reply = '{"blocks":[],"other_points":[],"action_items":[]}';
  private readonly server: Server;

  private constructor() {
    this.server = createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => chunks.push(chunk));
      req.on('end', () => {
        const path = req.url ?? '';
        this.requests.push({ method: req.method ?? '', path, body: Buffer.concat(chunks).toString('utf8') });
        res.setHeader('Content-Type', 'application/json');
        if (req.method === 'POST' && path.endsWith('/chat/completions')) {
          res.end(JSON.stringify({
            id: 'chatcmpl-stub', object: 'chat.completion', created: 0, model: 'stub-model',
            choices: [{ index: 0, finish_reason: 'stop', message: { role: 'assistant', content: this.reply } }],
            usage: { prompt_tokens: 1000, completion_tokens: 200, total_tokens: 1200 },
          }));
        } else {
          res.end('{"object":"list","data":[]}');
        }
      });
    });
  }

  static async start(): Promise<FakeModel> {
    const model = new FakeModel();
    await new Promise<void>((done) => model.server.listen(0, '127.0.0.1', done));
    return model;
  }

  get baseUrl(): string {
    return `http://127.0.0.1:${(this.server.address() as AddressInfo).port}/v1`;
  }

  async stop(): Promise<void> {
    await new Promise<void>((done) => this.server.close(() => done()));
  }
}

/** Fixed-port forwarder in front of the core, so the page keeps one endpoint across core restarts. */
async function startProxy(target: () => number | undefined): Promise<{ port: number; stop: () => Promise<void> }> {
  const server = createServer((req: IncomingMessage, res) => {
    const port = target();
    if (!port) {
      res.writeHead(502);
      res.end();
      return;
    }
    const upstream = httpRequest(
      { host: '127.0.0.1', port, method: req.method, path: req.url, headers: { ...req.headers, host: `127.0.0.1:${port}` } },
      (answer) => {
        res.writeHead(answer.statusCode ?? 502, answer.headers);
        answer.pipe(res);
      },
    );
    upstream.on('error', () => {
      if (!res.headersSent) res.writeHead(502);
      res.end();
    });
    res.on('close', () => upstream.destroy());
    req.pipe(upstream);
  });
  await new Promise<void>((done) => server.listen(0, '127.0.0.1', done));
  return {
    port: (server.address() as AddressInfo).port,
    stop: () => new Promise<void>((done) => { server.closeAllConnections(); server.close(() => done()); }),
  };
}

export type NotesStack = Awaited<ReturnType<typeof startNotesStack>>;

/**
 * The real core jar (MockAgent behind it), a Vite dev server, a fake model and a forwarder that survives core restarts.
 * Seeds golden meeting 1 straight into the database. Never a fake core.
 */
export async function startNotesStack(options: { model?: boolean } = {}) {
  const scratch = await mkdtemp(join(tmpdir(), 'interline-m2-'));
  const data = join(scratch, 'data');
  const logs = join(scratch, 'logs');
  await Promise.all([mkdir(data), mkdir(logs)]);
  const children: ManagedProcess[] = [];
  let core: ManagedProcess | undefined;
  let corePort: number | undefined;
  const model = options.model === false ? undefined : await FakeModel.start();
  const proxy = await startProxy(() => corePort);

  const startCore = async () => {
    core = launch(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : 'java', [
      '-jar', join(repository, 'services/core/app/target/interline-core.jar'),
      `--data-dir=${data}`, `--log-dir=${logs}`,
      `--agent=${join(repository, 'services/core/mock-agent/bin/mock-agent')}`,
      '--agent-arg=--speed=20', '--parent=stdio', `--allow-origin=${origin}`,
    ]);
    children.push(core);
    const pending = readyPort(core);
    core.child.stdin.write(`${JSON.stringify({ token })}\n`);
    corePort = await pending;
    return corePort !== undefined;
  };

  const stop = async () => {
    try {
      for (const child of [...children].reverse()) {
        try {
          await stopProcess(child);
        } catch {
          // The group of a core killed on purpose can be gone or a zombie (EPERM): keep cleaning up the others.
        }
      }
      await proxy.stop();
      await model?.stop();
    } finally {
      await rm(scratch, { recursive: true, force: true });
    }
  };

  try {
    const coreReady = await startCore();
    const viteEnv = { ...process.env, VITE_CORE_PORT: String(proxy.port), VITE_CORE_TOKEN: token };
    const vite = launch(process.execPath, [join(desktop, 'node_modules/vite/bin/vite.js'), desktop,
      '--host', '127.0.0.1', '--port', '5173', '--strictPort'], viteEnv);
    children.push(vite);
    let viteReady = false;
    const deadline = Date.now() + 10_000;
    while (!vite.finished() && Date.now() < deadline) {
      if (vite.output().includes(origin)) {
        try {
          viteReady = (await fetch(origin, { signal: AbortSignal.timeout(1_000) })).ok;
          if (viteReady) break;
        } catch { /* retry */ }
      }
      await delay(100);
    }

    const api = async (method: string, path: string, body?: unknown) => {
      const response = await fetch(`http://127.0.0.1:${proxy.port}/api/v1${path}`, {
        method,
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      return { status: response.status, text, json: () => JSON.parse(text) as unknown };
    };

    const query = <T = Record<string, unknown>>(sql: string, ...params: (string | number)[]): T[] => {
      const database = new DatabaseSync(join(data, 'db.sqlite'), { readOnly: true });
      try {
        return database.prepare(sql).all(...params) as T[];
      } finally {
        database.close();
      }
    };

    const { meeting, reply } = await loadGolden(1);
    const meetingId = 'gold-1';
    const seed = () => {
      const database = new DatabaseSync(join(data, 'db.sqlite'));
      try {
        database.exec('PRAGMA busy_timeout = 5000');
        database.prepare('INSERT INTO meetings(id,title,status,language,started_at,ended_at,t0_wall_ms,created_at,updated_at) VALUES (?,?,\'done\',?,?,?,?,?,?)')
          .run(meetingId, meeting.title, meeting.language, meeting.t0_wall_ms, meeting.t0_wall_ms + 90_000, meeting.t0_wall_ms, meeting.t0_wall_ms, meeting.t0_wall_ms);
        database.prepare('INSERT INTO speakers(id,meeting_id,source,label) VALUES (?,?,?,?)').run('spk-me', meetingId, 'mic', 'Me');
        database.prepare('INSERT INTO speakers(id,meeting_id,source,label) VALUES (?,?,?,?)').run('spk-them', meetingId, 'system', 'Them');
        for (const s of meeting.segments) {
          database.prepare('INSERT INTO transcript_segments(id,meeting_id,speaker_id,agent_seg,start_ms,end_ms,text,model) VALUES (?,?,?,?,?,?,?,\'golden\')')
            .run(s.id, meetingId, s.speaker === 'Me' ? 'spk-me' : 'spk-them', `seg-${s.id}`, s.start_ms, s.end_ms, s.text);
        }
      } finally {
        database.close();
      }
    };

    return {
      coreReady,
      viteReady,
      meetingId,
      meeting,
      reply,
      model,
      data,
      api,
      query,
      /** Insert the golden meeting 1 (transcript only). Call after the core is ready. */
      seed,
      /** PUT the golden notes as user blocks through the real API. */
      async putNotes() {
        for (const [i, note] of meeting.notes.entries()) {
          const response = await api('PUT', `/meetings/${meetingId}/blocks/${note.id}`, {
            client_rev: 1, kind: note.kind, position: `a${i}`, content: note.text, plain_text: note.text, anchor_ms: note.anchor_ms,
          });
          if (response.status !== 200) throw new Error(`PUT note ${note.id}: ${response.status} ${response.text}`);
        }
      },
      /** Save the fake model as the provider, with a test key. */
      async configureProvider() {
        const created = await api('POST', '/providers', {
          kind: 'openai_compatible', name: 'Stub', base_url: model!.baseUrl, model: 'stub-model', supports_json_schema: false,
        });
        if (created.status !== 200) throw new Error(`create provider: ${created.status} ${created.text}`);
        const id = (created.json() as { id: string }).id;
        const key = await api('PUT', `/providers/${id}/key`, { key: 'sk-test-e2e-0123456789' });
        if (key.status !== 204) throw new Error(`put key: ${key.status} ${key.text}`);
      },
      /** SIGKILL the whole core process group (core and agent), as a crash would. */
      async killCore() {
        corePort = undefined;
        if (core?.child.pid) {
          try { process.kill(-core.child.pid, 'SIGKILL'); } catch { /* already gone */ }
          await Promise.race([core.exited, delay(3_000)]);
        }
      },
      restartCore: startCore,
      diagnostics: () => `Core:\n${core?.output() ?? ''}\nVite:\n${vite.output()}`,
      stop,
    };
  } catch (error) {
    await stop();
    throw error;
  }
}

/** `test` with a `stacks` fixture: every stack it starts is stopped at teardown, even when the test times out. */
export const test = base.extend<{ stacks: (options?: { model?: boolean }) => Promise<NotesStack> }>({
  // eslint-disable-next-line no-empty-pattern
  stacks: async ({}, use, testInfo) => {
    const started: NotesStack[] = [];
    await use(async (options) => {
      const stack = await startNotesStack(options);
      started.push(stack);
      return stack;
    });
    for (const stack of started) {
      await testInfo.attach('notes-stack', { body: stack.diagnostics(), contentType: 'text/plain' });
      await stack.stop();
    }
  },
});

export { expect };
