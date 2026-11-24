import { expect, test } from '@playwright/test';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { withM1Stack } from './support/m1-stack';

// Real core, real Swift agent on --test-input, real whisper: proves the transcript panel shows what the pipeline
// transcribes, so the human sitting does not have to find that out. Needs a built agent and the speech models:
//   scripts/fixtures/gen-short-utterances.py fixtures/speech/short-system.wav
//   INTERLINE_E2E_MODELS=~/.interline-models pnpm test:e2e native-live
const modelsDir = process.env.INTERLINE_E2E_MODELS;
const SYSTEM_SENTENCE_WORDS = /renard.*chien/i;
const FIRST_FINAL_TIMEOUT_MS = 30_000;

test.skip(!modelsDir, 'set INTERLINE_E2E_MODELS to a directory holding ggml-small.bin and ggml-silero-v6.2.0.bin');

test('native agent: short system utterances appear as Them rows in the transcript panel', async ({ page }, info) => {
  const input = resolve(dirname(fileURLToPath(import.meta.url)), '../../../fixtures/speech/short-system.wav');
  await withM1Stack({ native: { input, modelsDir: modelsDir ?? '' } }, async (stack) => {
    expect(stack.coreReady, stack.diagnostics()).toBe(true);
    await page.goto('/');
    await page.getByRole('button', { name: 'Record', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Continue', exact: true }).click();
    const row = page.getByRole('list', { name: 'Transcript', exact: true }).getByRole('listitem')
      .filter({ hasText: /\bThem\b/ }).filter({ hasText: SYSTEM_SENTENCE_WORDS }).first();
    await expect(row).toBeVisible({ timeout: FIRST_FINAL_TIMEOUT_MS });
    await expect(row).not.toHaveAttribute('aria-busy', 'true', { timeout: FIRST_FINAL_TIMEOUT_MS });
  }, async (body) => { await info.attach('m1-stack', { body, contentType: 'text/plain' }); });
});
