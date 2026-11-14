import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { stripVTControlCharacters } from 'node:util';
import { setTimeout, clearTimeout } from 'node:timers';
import { setTimeout as delay } from 'node:timers/promises';

const desktop = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const repository = resolve(desktop, '../..');
const origin = 'http://127.0.0.1:5173';
// Public test-only 256-bit value, not a credential. Never passed to core via env or argv.
const token = Buffer.alloc(32, 0x14).toString('base64url');

type ManagedProcess = {
  child: ChildProcessWithoutNullStreams;
  exited: Promise<void>;
  finished: () => boolean;
  output: () => string;
};

function launch(command: string, args: string[], env = process.env): ManagedProcess {
  let output = '';
  let finished = false;
  const child = spawn(command, args, { cwd: repository, env, detached: true, stdio: 'pipe' });
  const append = (data: Buffer | string) => {
    output = (output + stripVTControlCharacters(data.toString()).split(token).join('[test-token]')).slice(-24_000);
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
  return { child, exited, finished: () => finished, output: () => output };
}

async function readyPort(core: ManagedProcess): Promise<number | undefined> {
  const lines = createInterface({ input: core.child.stdout });
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
      core.exited.then(() => undefined),
      new Promise<undefined>((done) => { timer = setTimeout(() => done(undefined), 15_000); }),
    ]);
  } finally {
    clearTimeout(timer);
    lines.close();
  }
}

function signalGroup(managed: ManagedProcess, signal: NodeJS.Signals) {
  if (!managed.child.pid) return;
  try {
    process.kill(-managed.child.pid, signal);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ESRCH') throw error;
  }
}

async function stopProcess(managed: ManagedProcess) {
  if (!managed.finished()) {
    managed.child.stdin.end('{"cmd":"shutdown"}\n');
    await Promise.race([managed.exited, delay(2_000)]);
  }
  // Signal the process group even if the parent already exited, including any agent.
  signalGroup(managed, 'SIGTERM');
  await Promise.race([managed.exited, delay(1_000)]);
  signalGroup(managed, 'SIGKILL');
  await Promise.race([managed.exited, delay(1_000)]);
}

export async function startRecordingStack() {
  const scratch = await mkdtemp(join(tmpdir(), 'interline-ac14-'));
  const data = join(scratch, 'data');
  const logs = join(scratch, 'logs');
  await Promise.all([mkdir(data), mkdir(logs)]);
  const children: ManagedProcess[] = [];
  const stop = async () => {
    try {
      for (const child of [...children].reverse()) await stopProcess(child);
    } finally {
      await rm(scratch, { recursive: true, force: true });
    }
  };
  try {
    const core = launch(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : 'java', [
      '-jar', join(repository, 'services/core/app/target/interline-core.jar'),
      `--data-dir=${data}`, `--log-dir=${logs}`,
      `--agent=${join(repository, 'services/core/mock-agent/bin/mock-agent')}`,
      '--agent-arg=--speed=20', '--parent=stdio', `--allow-origin=${origin}`,
    ]);
    children.push(core);
    const pendingReady = readyPort(core);
    core.child.stdin.write(`${JSON.stringify({ token })}\n`);
    const port = await pendingReady;

    // No globalSetup/webServer failure can mask the test's explicit READY assertion.
    // Only Vite receives the dev endpoint env; no generated source or fake API.
    const viteEnv = { ...process.env };
    delete viteEnv.VITE_CORE_PORT;
    delete viteEnv.VITE_CORE_TOKEN;
    if (port !== undefined) {
      viteEnv.VITE_CORE_PORT = String(port);
      viteEnv.VITE_CORE_TOKEN = token;
    }
    const vite = launch(process.execPath, [join(desktop, 'node_modules/vite/bin/vite.js'), desktop,
      '--host', '127.0.0.1', '--port', '5173', '--strictPort'], viteEnv);
    children.push(vite);
    let viteReady = false;
    const deadline = Date.now() + 10_000;
    while (!vite.finished() && Date.now() < deadline) {
      if (vite.output().includes(origin)) {
        try {
          const response = await fetch(origin, { signal: AbortSignal.timeout(1_000) });
          viteReady = response.ok;
          if (viteReady) break;
        } catch { /* Retry only our own Vite process. */ }
      }
      await delay(100);
    }
    return {
      coreReady: port !== undefined,
      viteReady,
      diagnostics: () => `Core:\n${core.output()}\nVite:\n${vite.output()}`,
      stop,
    };
  } catch (error) {
    await stop();
    throw error;
  }
}
