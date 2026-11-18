import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createInterface } from 'node:readline';
import { setTimeout, clearTimeout } from 'node:timers';
import { setTimeout as delay } from 'node:timers/promises';
import { stripVTControlCharacters } from 'node:util';
import { createServer, type Server } from 'node:http';
import { mkdtemp, mkdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import net from 'node:net';

const desktop = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const repository = resolve(desktop, '../..');
const origin = 'http://127.0.0.1:5173';
const MOCK_AGENT = join(repository, 'services/core/mock-agent/bin/mock-agent');
const NATIVE_AGENT = resolve(repository, 'agents/macos-capture/.build/release/interline-capture-macos');
const NATIVE_MODEL_FILES = ['ggml-small.bin', 'ggml-silero-v6.2.0.bin'];

// Public test-only 256-bit value, not a credential. Never passed to core via env or argv.
const DEFAULT_TOKEN = Buffer.alloc(32, 0x14).toString('base64url');

const CORE_OUTPUT_LIMIT = 256_000;
const VITE_OUTPUT_LIMIT = 32_000;
const READY_TIMEOUT_MS = 15_000;
const VITE_START_TIMEOUT_MS = 10_000;
const PORT_POLL_INTERVAL_MS = 100;

export type ManagedProcess = {
  child: ChildProcessWithoutNullStreams;
  exited: Promise<void>;
  finished: () => boolean;
  output: () => string;
  record: (text: string) => void;
};

type LaunchOptions = {
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  outputLimit?: number;
  sensitiveToken?: string;
};

export function launch(command: string, args: string[], options: LaunchOptions = {}): ManagedProcess {
  const { cwd = repository, env = process.env, outputLimit = VITE_OUTPUT_LIMIT, sensitiveToken } = options;
  const redact = sensitiveToken ? (text: string) => text.split(sensitiveToken).join('[test-token]') : (text: string) => text;
  let output = '';
  let finished = false;
  const child = spawn(command, args, { cwd, env, detached: true, stdio: 'pipe' });
  const append = (data: Buffer | string) => {
    output = (output + stripVTControlCharacters(data.toString())).slice(-outputLimit);
  };
  child.stdout.on('data', append);
  child.stderr.on('data', append);
  child.stdin.on('error', (error: Error) => append(`stdin: ${error.message}\n`));
  const exited = new Promise<void>((done) => {
    child.once('error', (error) => {
      append(`spawn: ${error.message}\n`);
      finished = true;
      done();
    });
    child.once('exit', (code, signal) => {
      append(`exit: ${code}, signal: ${signal}\n`);
      finished = true;
      done();
    });
  });
  return { child, exited, finished: () => finished, output: () => redact(output), record: append };
}

function signalProcessGroup(managed: ManagedProcess, signal: NodeJS.Signals) {
  if (!managed.child.pid) return;
  try {
    // Signal the group even when the parent already exited: its agent child may outlive it.
    process.kill(-managed.child.pid, signal);
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code === 'ESRCH') return;
    if (code !== 'EPERM') throw error;
    managed.record(`signal ${signal}: ${(error as Error).message}\n`);
  }
}

export async function waitForProcessExit(managed: ManagedProcess, timeoutMs: number): Promise<boolean> {
  if (managed.finished()) return true;
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    await Promise.race([managed.exited, new Promise<void>((done) => { timer = setTimeout(done, timeoutMs); })]);
  } finally {
    clearTimeout(timer);
  }
  return managed.finished();
}

export async function stopProcess(managed: ManagedProcess) {
  if (!managed.finished()) {
    managed.child.stdin.end('{"cmd":"shutdown"}\n');
    await waitForProcessExit(managed, 2_000);
  }
  signalProcessGroup(managed, 'SIGTERM');
  await waitForProcessExit(managed, 1_000);
  signalProcessGroup(managed, 'SIGKILL');
  await waitForProcessExit(managed, 1_000);
}

async function isPortOpen(port: number): Promise<boolean> {
  return new Promise((done) => {
    const socket = new net.Socket();
    socket.setTimeout(200);
    socket.once('connect', () => { socket.destroy(); done(true); });
    socket.once('timeout', () => { socket.destroy(); done(false); });
    socket.once('error', () => { socket.destroy(); done(false); });
    socket.connect(port, '127.0.0.1');
  });
}

export async function waitForPortFree(port: number, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (!(await isPortOpen(port))) return true;
    await delay(PORT_POLL_INTERVAL_MS);
  }
  return !(await isPortOpen(port));
}

export async function readReadyPort(managed: ManagedProcess): Promise<number | undefined> {
  const lines = createInterface({ input: managed.child.stdout });
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      new Promise<number>((done) => {
        lines.on('line', (line) => {
          try {
            const value = JSON.parse(line) as { event?: string; port?: number };
            if (value.event === 'ready' && Number.isInteger(value.port) && value.port! > 0 && value.port! <= 65535) {
              done(value.port!);
            }
          } catch {
            // Only the documented JSON READY line counts as readiness.
          }
        });
      }),
      managed.exited.then(() => undefined),
      new Promise<undefined>((done) => { timer = setTimeout(() => done(undefined), READY_TIMEOUT_MS); }),
    ]);
  } finally {
    clearTimeout(timer);
    lines.close();
  }
}

export async function waitForViteReady(vite: ManagedProcess): Promise<boolean> {
  const deadline = Date.now() + VITE_START_TIMEOUT_MS;
  while (!vite.finished() && Date.now() < deadline) {
    try {
      const response = await fetch(origin, { signal: AbortSignal.timeout(1_000) });
      if (response.ok) return true;
    } catch { /* Vite is still starting. */ }
    await delay(100);
  }
  return false;
}

export type StackOptions = {
  token?: string;
  dataDir?: string;
  logsDir?: string;
  jvmArgs?: string[];
  agentPath?: string;
  agentArgs?: string[];
  scratchDir?: string;
  cleanupScratch?: boolean;
  extraEnv?: Record<string, string | undefined>;
};

export type Stack = {
  coreReady: boolean;
  viteReady: boolean;
  diagnostics: () => string;
  stop: () => Promise<void>;
};

export async function launchStack(options: StackOptions = {}): Promise<Stack> {
  const token = options.token ?? DEFAULT_TOKEN;
  const agentPath = options.agentPath ?? MOCK_AGENT;
  const agentArgs = options.agentArgs ?? ['--speed=20'];
  const cleanupScratch = options.cleanupScratch ?? true;
  const scratch = options.scratchDir ?? await mkdtemp(join(tmpdir(), 'interline-stack-'));
  const data = options.dataDir ?? join(scratch, 'data');
  const logs = options.logsDir ?? join(scratch, 'logs');
  if (!options.dataDir) await mkdir(data, { recursive: true });
  if (!options.logsDir) await mkdir(logs, { recursive: true });

  let core: ManagedProcess | undefined;
  let vite: ManagedProcess | undefined;

  const stop = async () => {
    try {
      if (vite) await stopProcess(vite);
      if (core) await stopProcess(core);
      if (!(await waitForPortFree(5173, 5_000))) {
        throw new Error('Port 5173 was not released after stopping Vite.');
      }
    } finally {
      if (cleanupScratch) await rm(scratch, { recursive: true, force: true });
    }
  };

  try {
    if (!(await waitForPortFree(5173, 5_000))) {
      throw new Error('Port 5173 is still in use from a previous test; cannot start Vite.');
    }

    core = launch(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : 'java', [
      ...(options.jvmArgs ?? []),
      '-jar', join(repository, 'services/core/app/target/interline-core.jar'),
      `--data-dir=${data}`, `--log-dir=${logs}`,
      `--agent=${agentPath}`,
      ...agentArgs.map((arg) => `--agent-arg=${arg}`),
      '--parent=stdio', `--allow-origin=${origin}`,
    ], { outputLimit: CORE_OUTPUT_LIMIT, sensitiveToken: token });

    const pendingReady = readReadyPort(core);
    core.child.stdin.write(`${JSON.stringify({ token })}\n`);
    const port = await pendingReady;

    const viteEnv = { ...process.env, ...options.extraEnv };
    delete viteEnv.VITE_CORE_PORT;
    delete viteEnv.VITE_CORE_TOKEN;
    if (port !== undefined) {
      viteEnv.VITE_CORE_PORT = String(port);
      viteEnv.VITE_CORE_TOKEN = token;
    }

    vite = launch(process.execPath, [join(desktop, 'node_modules/vite/bin/vite.js'), desktop,
      '--host', '127.0.0.1', '--port', '5173', '--strictPort'], { env: viteEnv, sensitiveToken: token });
    const viteReady = await waitForViteReady(vite);

    return {
      coreReady: port !== undefined,
      viteReady,
      diagnostics: () => `Core:\n${core?.output() ?? ''}\nVite:\n${vite?.output() ?? ''}`,
      stop,
    };
  } catch (error) {
    await stop();
    throw error;
  }
}

// M1-specific helpers kept here so both stack files stay thin.

export type ModelFixture = {
  server: Server;
  fixtureRequests: string[];
  catalogPath: string;
  close: () => Promise<void>;
};

export async function startModelFixture(scratch: string): Promise<ModelFixture> {
  const bytes = Buffer.alloc(256 * 1024, 0x51);
  const fixtureRequests: string[] = [];
  const modelServer = createServer(async (request, response) => {
    fixtureRequests.push(`${request.method} ${request.url}`);
    if (request.url !== '/ggml-m1-checksum.bin') { response.writeHead(404).end(); return; }
    const offset = Number(/^bytes=(\d+)-$/.exec(request.headers.range ?? '')?.[1] ?? 0);
    response.writeHead(offset ? 206 : 200, {
      'Content-Type': 'application/octet-stream', 'Content-Length': bytes.length - offset,
      'Accept-Ranges': 'bytes', ...(offset ? { 'Content-Range': `bytes ${offset}-${bytes.length - 1}/${bytes.length}` } : {}),
    });
    for (let at = offset; at < bytes.length && !response.destroyed; at += 8192) {
      response.write(bytes.subarray(at, at + 8192));
      await delay(80);
    }
    response.end();
  });
  await new Promise<void>((done) => modelServer.listen(0, '127.0.0.1', done));
  const address = modelServer.address();
  if (!address || typeof address === 'string') throw new Error('HTTP fixture has no TCP address');
  const catalog = join(scratch, 'model-catalog.json');
  await writeFile(catalog, JSON.stringify([{
    id: 'm1-checksum', name: 'M1 checksum fixture',
    url: `http://127.0.0.1:${address.port}/ggml-m1-checksum.bin`, sizeBytes: bytes.length,
    sha256: createHash('sha256').update(Buffer.alloc(bytes.length, 0x52)).digest('hex'),
  }]));
  return {
    server: modelServer,
    fixtureRequests,
    catalogPath: catalog,
    close: async () => {
      modelServer.closeAllConnections();
      if (modelServer.listening) await new Promise<void>((done) => modelServer.close(() => done()));
    },
  };
}

export async function prepareNativeModels(dataDir: string, modelsDir: string): Promise<void> {
  await mkdir(join(dataDir, 'models'), { recursive: true });
  for (const file of NATIVE_MODEL_FILES) {
    await symlink(join(modelsDir, file), join(dataDir, 'models', file));
  }
}

export { MOCK_AGENT, NATIVE_AGENT, origin, repository, DEFAULT_TOKEN };
