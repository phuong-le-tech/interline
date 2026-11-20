import { expect, test } from '@playwright/test';
import { startRecordingStack } from './support/recording-stack';

test('AC14_live - Me and Them appear before Stop and the meeting finishes done', async ({ page }, testInfo) => {
  const stack = await startRecordingStack();
  try {
    expect(stack.viteReady, `Vite must serve the desktop. ${stack.diagnostics()}`).toBe(true);
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Interline' })).toBeVisible();
    expect(stack.coreReady, `Core must emit READY before recording. ${stack.diagnostics()}`).toBe(true);
    const record = page.getByRole('button', { name: 'Record', exact: true });
    await expect(record).toBeVisible();
    await record.click();
    await page.getByRole('dialog').getByRole('button', { name: 'Continue', exact: true }).click();

    const transcript = page.getByRole('list', { name: 'Transcript', exact: true });
    await expect(transcript.getByRole('listitem').filter({ hasText: /\bMe\b/ }).first()).toBeVisible();
    await expect(transcript.getByRole('listitem').filter({ hasText: /\bThem\b/ }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Stop', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Stop', exact: true }).click();

    const meetings = page.getByRole('list', { name: 'Meetings', exact: true });
    await expect(meetings.getByRole('listitem').filter({ hasText: /\bdone\b/ }).first()).toBeVisible();
    await expect(page.getByRole('status')).toHaveText('done');
  } finally {
    await stack.stop();
    await testInfo.attach('recording-stack', { body: stack.diagnostics(), contentType: 'text/plain' });
  }
});
