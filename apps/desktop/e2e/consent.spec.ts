import { expect, test } from '@playwright/test';
import { startRecordingStack } from './support/recording-stack';

// Consent is a UI reminder. The direct recording API remains usable without an acknowledgement.
test('first Record waits for Continue; the same meeting skips the reminder after reload', async ({ page }, info) => {
  const stack = await startRecordingStack();
  try {
    expect(stack.coreReady, stack.diagnostics()).toBe(true);
    expect(stack.viteReady, stack.diagnostics()).toBe(true);
    const starts: string[] = [];
    const acknowledgements: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/recording/start')) starts.push(request.url());
      if (request.method() === 'POST' && request.url().endsWith('/consent-ack')) acknowledgements.push(request.url());
    });
    await page.goto('/');
    await page.getByRole('button', { name: 'Record', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: /consent|record/i });
    await expect(dialog).toBeVisible();
    expect(starts).toHaveLength(0);
    expect(acknowledgements).toHaveLength(0);
    const started = page.waitForResponse((response) => response.url().endsWith('/recording/start'));
    await dialog.getByRole('button', { name: 'Continue', exact: true }).click();
    const first = await started;
    expect(first.ok()).toBe(true);
    const id = (await first.json() as { meetingId: string }).meetingId;
    expect(acknowledgements).toHaveLength(1);
    expect(acknowledgements[0]).toContain(`/meetings/${id}/consent-ack`);
    await expect(page.getByRole('button', { name: 'Stop', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Stop', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('done');
    await page.reload();
    await page.getByRole('list', { name: 'Meetings', exact: true }).getByRole('button', { name: 'Open notes' }).first().click();
    const resumed = page.waitForResponse((response) => response.url().endsWith('/recording/start'));
    await page.getByRole('button', { name: 'Record', exact: true }).click();
    expect((await (await resumed).json() as { meetingId: string }).meetingId).toBe(id);
    await expect(dialog).toBeHidden();
    expect(acknowledgements).toHaveLength(1);
    expect(starts).toHaveLength(2);
  } finally {
    await stack.stop();
    await info.attach('consent-stack', { body: stack.diagnostics(), contentType: 'text/plain' });
  }
});

test('Cancel and Escape leave no acknowledgement or recording; opt out skips a new meeting after reload', async ({ page }, info) => {
  const stack = await startRecordingStack();
  try {
    expect(stack.coreReady, stack.diagnostics()).toBe(true);
    expect(stack.viteReady, stack.diagnostics()).toBe(true);
    const writes: string[] = [];
    const creations: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && /\/(consent-ack|recording\/start)$/.test(request.url())) writes.push(request.url());
      if (request.method() === 'POST' && request.url().endsWith('/meetings')) creations.push(request.url());
    });
    await page.goto('/');
    const record = page.getByRole('button', { name: 'Record', exact: true });
    const dialog = page.getByRole('dialog', { name: /consent|record/i });
    await record.click();
    await expect(dialog).toBeVisible();
    expect(creations).toHaveLength(0);
    await dialog.getByRole('checkbox', { name: /do not remind me again/i }).check();
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(dialog).toBeHidden();
    expect(writes).toHaveLength(0);
    expect(creations).toHaveLength(0);
    await page.reload();
    await expect(page.getByRole('list', { name: 'Meetings', exact: true }).getByRole('listitem')).toHaveCount(0);
    await record.click();
    await expect(dialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    expect(writes).toHaveLength(0);
    expect(creations).toHaveLength(0);
    await record.click();
    await expect(dialog).toBeVisible();
    await dialog.getByRole('checkbox', { name: /do not remind me again/i }).check();
    const saved = page.waitForResponse((response) => response.request().method() === 'PUT'
      && response.url().endsWith('/settings/consent'));
    await dialog.getByRole('button', { name: 'Continue', exact: true }).click();
    expect((await saved).ok()).toBe(true);
    await expect(page.getByRole('button', { name: 'Stop', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Stop', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('done');
    await page.reload();
    await page.getByLabel('Meeting title', { exact: true }).fill('New meeting after opting out');
    await page.getByRole('button', { name: 'New meeting', exact: true }).click();
    const started = page.waitForResponse((response) => response.url().endsWith('/recording/start'));
    await record.click();
    expect((await started).ok()).toBe(true);
    await expect(dialog).toBeHidden();
  } finally {
    await stack.stop();
    await info.attach('consent-stack', { body: stack.diagnostics(), contentType: 'text/plain' });
  }
});

test('failed acknowledgement keeps reminder open and starts no recording', async ({ page }, info) => {
  const stack = await startRecordingStack();
  try {
    expect(stack.coreReady, stack.diagnostics()).toBe(true);
    expect(stack.viteReady, stack.diagnostics()).toBe(true);
    let starts = 0;
    let creations = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/recording/start')) starts++;
      if (request.method() === 'POST' && request.url().endsWith('/meetings')) creations++;
    });
    await page.route('**/meetings/*/consent-ack', (route) => route.fulfill({ status: 500,
      contentType: 'application/problem+json', body: JSON.stringify({ detail: 'Acknowledgement unavailable' }) }));
    await page.goto('/');
    await page.getByRole('button', { name: 'Record', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: /consent|record/i });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Continue', exact: true }).click();
    await expect(page.getByRole('alert')).toHaveText('Something went wrong. Please try again.');
    await expect(page.getByRole('alert')).not.toContainText('Acknowledgement unavailable');
    await expect(dialog).toBeVisible();
    expect(starts).toBe(0);
    expect(creations).toBe(1);
    const retried = page.waitForResponse((response) => response.url().endsWith('/consent-ack'));
    await dialog.getByRole('button', { name: 'Continue', exact: true }).click();
    expect((await retried).status()).toBe(500);
    expect(creations).toBe(1);
    expect(starts).toBe(0);
  } finally {
    await stack.stop();
    await info.attach('consent-stack', { body: stack.diagnostics(), contentType: 'text/plain' });
  }
});
