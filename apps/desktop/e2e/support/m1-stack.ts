import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, rm, symlink, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { stripVTControlCharacters } from 'node:util';
import { startRecordingStack } from './recording-stack';

const desktop = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const repository = resolve(desktop, '../..');
const origin = 'http://127.0.0.1:5173';
const NATIVE_AGENT = resolve(repository, 'agents/macos-capture/.build/release/interline-capture-macos');
const NATIVE_MODEL_FILES = ['ggml-small.bin', 'ggml-silero-v6.2.0.bin'];
const token = Buffer.alloc(32, 0x12).toString('base64url');

type Process = {
  child: ChildProcessWithoutNullStreams;
  exited: Promise<void>;
  output: () => string;
};

function launch(command: string, args: string[], env = process.env): Process {
  const child = spawn(command, args, { cwd: repository, env, detached: true, stdio: 'pipe' });
  let output = '';
  const append = (data: Buffer | string) => {
    output = (output + stripVTControlCharacters(String(data)).split(token).join('[test-token]')).slice(-32_000);
  };
  child.stdout.on('data', append);
  child.stderr.on('data', append);
  child.stdin.on('error', (error: Error) => append(error.message));
  const exited = new Promise<void>((done) => {
    child.once('error', (error) => { append(error.message); done(); });
    child.once('exit', (code, signal) => { append(`\nexit=${code} signal=${signal}\n`); done(); });
  });
  return { child, exited, output: () => output };
}

async function stopProcess(managed: Process) {
  managed.child.stdin.end('{"cmd":"shutdown"}\n');
  await Promise.race([managed.exited, delay(2_000)]);
  for (const signal of ['SIGTERM', 'SIGKILL'] as const) {
    if (managed.child.pid) {
      try { process.kill(-managed.child.pid, signal); } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error;
      }
    }
    await Promise.race([managed.exited, delay(500)]);
  }
}

/** The existing M0 helper stays untouched. Only fault/configuration runs need this launcher. */
export type NativeAgent = { input: string; modelsDir: string };

export async function startM1Stack(options: { agentArgs?: string[]; modelDownload?: boolean; native?: NativeAgent } = {}) {
  if (!options.agentArgs && !options.modelDownload && !options.native) return startRecordingStack();
  const scratch = await mkdtemp(join(tmpdir(), 'interline-m1-'));
  const children: Process[] = [];
  const fixtureRequests: string[] = [];
  // External HTTP is the only fake: core, agent, database and SSE stay real.
  const bytes = Buffer.alloc(256 * 1024, 0x51);
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
  const stop = async () => {
    try {
      for (const child of [...children].reverse()) await stopProcess(child);
    } finally {
      modelServer.closeAllConnections();
      if (modelServer.listening) await new Promise<void>((done) => modelServer.close(() => done()));
      await rm(scratch, { recursive: true, force: true });
    }
  };
  try {
    const data = join(scratch, 'data');
    const logs = join(scratch, 'logs');
    await Promise.all([mkdir(data), mkdir(logs)]);
    const jvmArgs: string[] = [];
    if (options.native) {
      // Core installs nothing: it finds the speech models where the packaged app keeps them, in <data>/models.
      await mkdir(join(data, 'models'));
      for (const file of NATIVE_MODEL_FILES) await symlink(join(options.native.modelsDir, file), join(data, 'models', file));
    }
    if (options.modelDownload) {
      await new Promise<void>((done) => modelServer.listen(0, '127.0.0.1', done));
      const address = modelServer.address();
      if (!address || typeof address === 'string') throw new Error('HTTP fixture has no TCP address');
      const catalog = join(scratch, 'model-catalog.json');
      // Proposed M1 configuration seam, not a new strict core CLI option. M0 ignores it.
      // Hash the intended bytes, then serve different bytes to exercise real verification.
      await writeFile(catalog, JSON.stringify([{ id: 'm1-checksum', name: 'M1 checksum fixture',
        url: `http://127.0.0.1:${address.port}/ggml-m1-checksum.bin`, sizeBytes: bytes.length,
        sha256: createHash('sha256').update(Buffer.alloc(bytes.length, 0x52)).digest('hex') }]));
      jvmArgs.push(`-Dinterline.models.catalog=${catalog}`);
    }
    const core = launch(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : 'java', [
      ...jvmArgs, '-jar', join(repository, 'services/core/app/target/interline-core.jar'),
      `--data-dir=${data}`, `--log-dir=${logs}`,
      `--agent=${options.native ? NATIVE_AGENT : join(repository, 'services/core/mock-agent/bin/mock-agent')}`,
      ...(options.native ? [`--test-input=${options.native.input}`, '--test-speed=1'] : options.agentArgs ?? ['--speed=20'])
        .map((arg) => `--agent-arg=${arg}`),
      '--parent=stdio', `--allow-origin=${origin}`,
    ]);
    children.push(core);
    const lines = createInterface({ input: core.child.stdout });
    let timer: ReturnType<typeof setTimeout> | undefined;
    const ready = new Promise<number | undefined>((done) => {
      timer = setTimeout(() => done(undefined), 15_000);
      lines.on('line', (line) => {
        try {
          const value = JSON.parse(line) as { event?: string; port?: number };
          if (value.event === 'ready' && Number.isInteger(value.port) && value.port! > 0) done(value.port);
        } catch { /* Only the JSON READY line counts. */ }
      });
      void core.exited.then(() => done(undefined));
    });
    core.child.stdin.write(`${JSON.stringify({ token })}\n`);
    const port = await ready;
    clearTimeout(timer);
    lines.close();
    const env = { ...process.env };
    delete env.VITE_CORE_PORT;
    delete env.VITE_CORE_TOKEN;
    if (port) { env.VITE_CORE_PORT = String(port); env.VITE_CORE_TOKEN = token; }
    const vite = launch(process.execPath, [join(desktop, 'node_modules/vite/bin/vite.js'), desktop,
      '--host', '127.0.0.1', '--port', '5173', '--strictPort'], env);
    children.push(vite);
    let viteReady = false;
    const deadline = Date.now() + 10_000;
    while (Date.now() < deadline) {
      if (vite.output().includes(origin)) {
        try { viteReady = (await fetch(origin, { signal: AbortSignal.timeout(1000) })).ok; } catch { /* Retry. */ }
        if (viteReady) break;
      }
      await delay(100);
    }
    return { coreReady: port !== undefined, viteReady, stop,
      diagnostics: () => `Core:\n${core.output()}\nVite:\n${vite.output()}\nHTTP fixture:\n${fixtureRequests.join('\n')}`,
      fixtureRequests,
    };
  } catch (error) { await stop(); throw error; }
}

/** Always run cleanup and attach real process output, including for expected UI reds. */
export async function withM1Stack(
  options: Parameters<typeof startM1Stack>[0],
  run: (stack: Awaited<ReturnType<typeof startM1Stack>>) => Promise<void>,
  attach: (body: string) => Promise<void>,
) {
  const stack = await startM1Stack(options);
  try { await run(stack); } finally {
    await stack.stop();
    await attach(stack.diagnostics());
  }
}
