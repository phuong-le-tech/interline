import { launchStack } from './stack';

export async function startRecordingStack() {
  return launchStack({
    agentArgs: ['--speed=20'],
  });
}
