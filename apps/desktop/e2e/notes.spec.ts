import { type Page } from '@playwright/test';
import { expect, test, type NotesStack } from './support/notes-stack';

/**
 * UI contract used by the M2 specs (the code-writer builds the UI to satisfy it):
 *  - the Meetings list item of a meeting holds a button named "Open notes";
 *  - the notes editor is a textbox named "Notes"; every block element carries data-block-id, data-origin (user|ai)
 *    and, for AI blocks, data-ai-state (proposed|accepted|edited); AI blocks hold the sr-only text "AI addition";
 *  - the transcript of the opened meeting is a list named "Transcript" whose items carry data-segment-id and
 *    data-highlighted="true" while highlighted;
 *  - an "Enhance" button (disabled with the text "Configure a provider" until a provider exists), a radiogroup
 *    named "Version" with radios "Mine" and "Enhanced", a role=alert region for errors;
 *  - while the core does not answer, a status banner reads "Interline is restarting its engine".
 */

async function openNotes(page: Page, stack: NotesStack) {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Interline' })).toBeVisible();
  const item = page.getByRole('list', { name: 'Meetings' }).getByRole('listitem').filter({ hasText: stack.meeting.title });
  await item.getByRole('button', { name: 'Open notes' }).click();
  const editor = page.getByRole('textbox', { name: 'Notes' });
  await expect(editor).toBeVisible();
  return editor;
}

async function withStack(
  stacks: (options?: { model?: boolean }) => Promise<NotesStack>,
  options: { provider?: boolean; notes?: boolean },
  body: (stack: NotesStack) => Promise<void>,
) {
  const stack = await stacks();
  expect(stack.viteReady, `Vite must serve the desktop. ${stack.diagnostics()}`).toBe(true);
  expect(stack.coreReady, `Core must emit READY. ${stack.diagnostics()}`).toBe(true);
  stack.seed();
  if (options.provider) await stack.configureProvider();
  if (options.notes) await stack.putNotes();
  await body(stack);
}

const userTexts = (stack: NotesStack) =>
  stack.query<{ plain_text: string }>("SELECT plain_text FROM note_blocks WHERE meeting_id = ? AND origin = 'user' ORDER BY position", stack.meetingId)
    .map((row) => row.plain_text);

test('AC1_reloadIdentical - typed blocks persist with their id and anchor and a reload shows the same text', async ({ page, stacks }) => {
  await withStack(stacks, {}, async (stack) => {
    const editor = await openNotes(page, stack);
    await editor.click();
    await page.keyboard.type('Budget valide, réunion lundi');
    await page.keyboard.press('Enter');
    await page.keyboard.type('Relancer le client');

    await expect.poll(() => userTexts(stack), { timeout: 15_000 }).toEqual(['Budget valide, réunion lundi', 'Relancer le client']);
    const rows = stack.query<{ id: string; origin: string; anchor_ms: number }>(
      'SELECT id, origin, anchor_ms FROM note_blocks WHERE meeting_id = ? ORDER BY position', stack.meetingId);
    expect(rows).toHaveLength(2);
    for (const row of rows) {
      expect(row.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
      expect(row.origin).toBe('user');
      expect(row.anchor_ms).toBeGreaterThanOrEqual(0);
    }

    await page.reload();
    const reloaded = await openNotes(page, stack);
    await expect(reloaded).toHaveText(/Budget valide, réunion lundi\s*Relancer le client/);
    expect(stack.query<{ id: string }>('SELECT id FROM note_blocks WHERE meeting_id = ? ORDER BY position', stack.meetingId)
      .map((row) => row.id)).toEqual(rows.map((row) => row.id));
  });
});

test('AC2_journalReplay - killing the core while typing loses no keystroke', async ({ page, stacks }) => {
  await withStack(stacks, {}, async (stack) => {
    const editor = await openNotes(page, stack);
    await editor.click();
    await page.keyboard.type('Premier point avant la panne', { delay: 15 });
    await expect.poll(() => userTexts(stack), { timeout: 15_000 }).toEqual(['Premier point avant la panne']);

    await stack.killCore();
    await page.keyboard.type(' et la suite pendant la panne', { delay: 15 });
    await page.keyboard.press('Enter');
    await page.keyboard.type('Deuxieme point tape sans core', { delay: 15 });
    await expect(page.getByRole('status').filter({ hasText: 'Interline is restarting its engine' })).toBeVisible();
    await expect(editor).toContainText('Deuxieme point tape sans core');

    expect(await stack.restartCore()).toBe(true);

    await expect.poll(() => userTexts(stack), { timeout: 30_000 }).toEqual([
      'Premier point avant la panne et la suite pendant la panne',
      'Deuxieme point tape sans core',
    ]);
    await expect(page.getByRole('status').filter({ hasText: 'Interline is restarting its engine' })).toBeHidden();
  });
});

test('AC7_formatMessage - invalid model output shows the format message and leaves the notes untouched', async ({ page, stacks }) => {
  await withStack(stacks, { provider: true, notes: true }, async (stack) => {
    stack.model!.reply = 'Sure! Here are your improved notes.';
    const editor = await openNotes(page, stack);
    const before = await editor.innerText();

    await page.getByRole('button', { name: 'Enhance' }).click();

    await expect(page.getByRole('alert').filter({ hasText: 'The model did not respect the format' })).toBeVisible();
    expect(await editor.innerText()).toBe(before);
    expect(stack.query('SELECT id FROM note_blocks WHERE origin = ?', 'ai')).toHaveLength(0);
    expect(stack.query<{ status: string }>("SELECT status FROM ai_runs WHERE kind = 'enhance'")).toEqual([{ status: 'invalid_output' }]);
  });
});

test('AC11_enhanceDisabled - without a provider Enhance is disabled and says Configure a provider', async ({ page, stacks }) => {
  await withStack(stacks, { notes: true }, async (stack) => {
    await openNotes(page, stack);

    await expect(page.getByRole('button', { name: 'Enhance' })).toBeDisabled();
    await expect(page.getByText('Configure a provider')).toBeVisible();
    expect(stack.model!.requests).toHaveLength(0);
  });
});
