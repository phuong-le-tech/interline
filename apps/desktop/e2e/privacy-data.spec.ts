import { expect, test } from '@playwright/test';
import { mkdtemp, mkdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { launchStack } from './support/stack';

test('delete, trash, restore and erase; retention persists across a core restart', async ({ page }, info) => {
  const scratch = await mkdtemp(join(tmpdir(), 'interline-privacy-'));
  const dataDir = join(scratch, 'data');
  await mkdir(dataDir);
  const options = { scratchDir: scratch, dataDir, cleanupScratch: false };
  let stack = await launchStack(options);
  try {
    expect(stack.coreReady, stack.diagnostics()).toBe(true);
    expect(stack.viteReady, stack.diagnostics()).toBe(true);
    await page.goto('/');
    await page.getByLabel('Meeting title', { exact: true }).fill('Lifecycle meeting');
    const created = page.waitForResponse((response) => response.request().method() === 'POST' && response.url().endsWith('/meetings'));
    await page.getByRole('button', { name: 'New meeting', exact: true }).click();
    expect((await created).ok()).toBe(true);
    await page.reload();
    const remove = page.getByRole('button', { name: 'Delete Lifecycle meeting', exact: true });
    await remove.click();
    const confirmation = page.getByRole('dialog');
    await expect(confirmation).toContainText('restore it for 30 days');
    await confirmation.getByRole('button', { name: 'Move to trash', exact: true }).click();
    await expect(remove).toHaveCount(0);
    await page.getByRole('button', { name: 'Privacy and data', exact: true }).click();
    await expect(page.getByRole('status').filter({ hasText: 'FileVault status is unknown.' })).toHaveCSS('border-inline-start-color', 'rgb(168, 122, 44)');
    await page.getByRole('button', { name: 'Restore Lifecycle meeting', exact: true }).click();
    await expect(page.getByText('Meeting restored.', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Back to meetings', exact: true }).click();
    await remove.click();
    await confirmation.getByRole('button', { name: 'Move to trash', exact: true }).click();
    await expect(confirmation).toHaveCount(0);
    await page.getByRole('button', { name: 'Privacy and data', exact: true }).click();
    await page.getByRole('button', { name: 'Permanently delete Lifecycle meeting', exact: true }).click();
    await expect(confirmation).toContainText('cannot be undone');
    await confirmation.getByRole('button', { name: 'Delete permanently', exact: true }).click();
    await expect(page.getByRole('list', { name: 'Trashed meetings' }).getByRole('listitem')).toHaveCount(0);
    const retention = page.getByRole('combobox', { name: 'Audio retention', exact: true });
    await expect(retention).toBeEnabled();
    await retention.selectOption('30d');
    await expect(page.getByText('Audio retention saved.', { exact: true })).toBeVisible();
    await stack.stop();
    stack = await launchStack(options);
    expect(stack.coreReady, stack.diagnostics()).toBe(true);
    expect(stack.viteReady, stack.diagnostics()).toBe(true);
    await page.goto('/');
    await page.getByRole('button', { name: 'Privacy and data', exact: true }).click();
    await expect(retention).toHaveValue('30d');
    await expect(page.getByRole('list', { name: 'Trashed meetings' }).getByRole('listitem')).toHaveCount(0);
  } finally {
    await stack.stop();
    await info.attach('privacy-stack', { body: stack.diagnostics(), contentType: 'text/plain' });
    await rm(scratch, { recursive: true, force: true });
  }
});
