import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { stripVTControlCharacters } from 'node:util';
import { setTimeout, clearTimeout } from 'node:timers';
import { setTimeout as delay } from 'node:timers/promises';

export const desktop = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
export const repository = resolve(desktop, '../..');
export const origin = 'http://127.0.0.1:5173';
// Public test-only 256-bit value, not a credential. Never passed to core via env or argv.
export const token = Buffer.alloc(32, 0x14).toString('base64url');

export type ManagedProcess = {
  child: ChildProcessWithoutNullStreams;
  exited: Promise<void>;
  finished: () => boolean;
  output: () => string;
};

export function launch(command: string, args: string[], env = process.env): ManagedProcess {
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

export async function readyPort(core: ManagedProcess): Promise<number | undefined> {
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

export async function stopProcess(managed: ManagedProcess) {
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
import { launchStack } from './stack';

export async function startRecordingStack() {
  return launchStack({
    agentArgs: ['--speed=20'],
  });
}
