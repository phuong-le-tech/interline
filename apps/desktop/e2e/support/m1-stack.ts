import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchStack, startModelFixture, prepareNativeModels, NATIVE_AGENT, MOCK_AGENT, DEFAULT_TOKEN } from './stack';
import { startRecordingStack } from './recording-stack';

export type NativeAgent = { input: string; modelsDir: string };

export async function startM1Stack(options: { agentArgs?: string[]; modelDownload?: boolean; native?: NativeAgent } = {}) {
  if (!options.agentArgs && !options.modelDownload && !options.native) return startRecordingStack();

  const scratch = await mkdtemp(join(tmpdir(), 'interline-m1-'));
  let modelFixture: Awaited<ReturnType<typeof startModelFixture>> | undefined;

  const stop = async () => {
    try {
      await stack.stop();
    } finally {
      if (modelFixture) await modelFixture.close();
      await rm(scratch, { recursive: true, force: true });
    }
  };

  let stack: Awaited<ReturnType<typeof launchStack>>;
  try {
    const jvmArgs: string[] = [];
    const data = join(scratch, 'data');
    const logs = join(scratch, 'logs');

    if (options.native) {
      await prepareNativeModels(data, options.native.modelsDir);
    }

    if (options.modelDownload) {
      modelFixture = await startModelFixture(scratch);
      jvmArgs.push(`-Dinterline.models.catalog=${modelFixture.catalogPath}`);
    }

    stack = await launchStack({
      token: DEFAULT_TOKEN,
      scratchDir: scratch,
      cleanupScratch: false,
      dataDir: data,
      logsDir: logs,
      jvmArgs,
      agentPath: options.native ? NATIVE_AGENT : MOCK_AGENT,
      agentArgs: options.native
        ? [`--test-input=${options.native.input}`, '--test-speed=1']
        : options.agentArgs ?? ['--speed=20'],
    });

    return {
      coreReady: stack.coreReady,
      viteReady: stack.viteReady,
      diagnostics: () => `${stack.diagnostics()}\nHTTP fixture:\n${modelFixture?.fixtureRequests.join('\n') ?? ''}`,
      fixtureRequests: modelFixture?.fixtureRequests ?? [],
      stop,
    };
  } catch (error) {
    await stop();
    throw error;
  }
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
