import { expect, test } from '@playwright/test';
import { startNotesStack } from './support/notes-stack';

/** AC17: Cmd+K palette over blocks and transcript. Same UI contract as notes.spec.ts, plus: a dialog named "Search" with a combobox and options. */

test('AC17_palette - Mod+K opens, arrows and Enter navigate to a result, Escape closes', async ({ page }, testInfo) => {
  const stack = await startNotesStack({ model: false });
  try {
    expect(stack.viteReady, `Vite must serve the desktop. ${stack.diagnostics()}`).toBe(true);
    expect(stack.coreReady, `Core must emit READY. ${stack.diagnostics()}`).toBe(true);
    stack.seed();
    await stack.putNotes();
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Interline' })).toBeVisible();
    const dialog = page.getByRole('dialog', { name: 'Search' });
    await expect(dialog).toBeHidden();

    await page.keyboard.press('ControlOrMeta+K');
    await expect(dialog).toBeVisible();
    const input = dialog.getByRole('combobox');
    await expect(input).toBeFocused();
    await input.fill('reunion');

    const options = dialog.getByRole('option');
    await expect(options).toHaveCount(3);
    await expect(options.filter({ hasText: 'réunion cadrage' })).toHaveCount(1);
    await page.keyboard.press('ArrowDown');
    await expect(options.first()).toHaveAttribute('aria-selected', 'true');
    await page.keyboard.press('ArrowDown');
    await expect(options.nth(1)).toHaveAttribute('aria-selected', 'true');
    await page.keyboard.press('ArrowUp');
    await expect(options.first()).toHaveAttribute('aria-selected', 'true');
    await page.keyboard.press('Enter');

    await expect(dialog).toBeHidden();
    await expect(page.getByRole('textbox', { name: 'Notes' })).toBeVisible();
    await expect(page.getByRole('list', { name: 'Transcript' })).toBeVisible();

    await page.keyboard.press('ControlOrMeta+K');
    await expect(dialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
  } finally {
    await stack.stop();
    await testInfo.attach('notes-stack', { body: stack.diagnostics(), contentType: 'text/plain' });
  }
});

test('AC17_palette - a segment result highlights the segment in the transcript', async ({ page }, testInfo) => {
  const stack = await startNotesStack({ model: false });
  try {
    expect(stack.viteReady, `Vite must serve the desktop. ${stack.diagnostics()}`).toBe(true);
    stack.seed();
    await page.goto('/');
    await page.keyboard.press('ControlOrMeta+K');
    const dialog = page.getByRole('dialog', { name: 'Search' });
    await dialog.getByRole('combobox').fill('contrats');

    await dialog.getByRole('option').filter({ hasText: 'trois contrats sont en attente' }).click();

    await expect(page.getByRole('list', { name: 'Transcript' }).locator('[data-segment-id="1004"]'))
      .toHaveAttribute('data-highlighted', 'true');
  } finally {
    await stack.stop();
    await testInfo.attach('notes-stack', { body: stack.diagnostics(), contentType: 'text/plain' });
  }
});

test('AC17_palette - a query with no match says so', async ({ page }, testInfo) => {
  const stack = await startNotesStack({ model: false });
  try {
    expect(stack.viteReady, `Vite must serve the desktop. ${stack.diagnostics()}`).toBe(true);
    stack.seed();
    await page.goto('/');
    await page.keyboard.press('ControlOrMeta+K');
    const dialog = page.getByRole('dialog', { name: 'Search' });

    await dialog.getByRole('combobox').fill('zzzabsentzzz');

    await expect(dialog.getByRole('option')).toHaveCount(0);
    await expect(dialog.getByText('No results')).toBeVisible();
  } finally {
    await stack.stop();
    await testInfo.attach('notes-stack', { body: stack.diagnostics(), contentType: 'text/plain' });
  }
});
