import { type Page } from '@playwright/test';
import { expect, test, type NotesStack } from './support/notes-stack';

/** Same UI contract as notes.spec.ts. Golden meeting 1 with its stub reply: 3 additions, 1 other point, 2 actions. */

async function withEnhanced(
  page: Page,
  stacks: (options?: { model?: boolean }) => Promise<NotesStack>,
  body: (stack: NotesStack) => Promise<void>,
) {
  const stack = await stacks();
  expect(stack.viteReady, `Vite must serve the desktop. ${stack.diagnostics()}`).toBe(true);
  expect(stack.coreReady, `Core must emit READY. ${stack.diagnostics()}`).toBe(true);
  stack.seed();
  await stack.configureProvider();
  await stack.putNotes();
  stack.model!.reply = stack.reply;
  await page.goto('/');
  await page.getByRole('list', { name: 'Meetings' }).getByRole('listitem').filter({ hasText: stack.meeting.title })
    .getByRole('button', { name: 'Open notes' }).click();
  await expect(page.getByRole('textbox', { name: 'Notes' })).toBeVisible();
  await page.getByRole('button', { name: 'Enhance' }).click();
  await expect(page.locator('[data-origin="ai"]')).toHaveCount(4);
  await body(stack);
}

test('AC9_toggle - Mine hides the AI additions and Enhanced brings them back, user text stays', async ({ page, stacks }) => {
  await withEnhanced(page, stacks, async (stack) => {
    const ai = page.locator('[data-origin="ai"]');
    const user = page.locator('[data-origin="user"]');
    await expect(user).toHaveCount(3);
    await expect(page.getByRole('radiogroup', { name: 'Version' }).getByRole('radio', { name: 'Enhanced' })).toBeChecked();

    await page.getByRole('radio', { name: 'Mine' }).check();
    await expect(ai.first()).toBeHidden();
    await expect(user.first()).toContainText(stack.meeting.notes[0].text.trim());
    await expect(user).toHaveCount(3);

    await page.getByRole('radio', { name: 'Enhanced' }).check();
    await expect(ai.first()).toBeVisible();
  });
});

test('AC9_label - every AI addition carries the screen reader label "AI addition" and a citation chip', async ({ page, stacks }) => {
  await withEnhanced(page, stacks, async () => {
    const ai = page.locator('[data-origin="ai"]');

    for (const block of await ai.all()) {
      await expect(block.getByText('AI addition')).toHaveCount(1);
      await expect(block.getByRole('button', { name: /^Source/ }).first()).toBeVisible();
      await expect(block).toHaveAttribute('data-ai-state', 'proposed');
    }
  });
});

test('AC9_accept_delete - Mod+Enter accepts the focused addition and Backspace deletes it', async ({ page, stacks }) => {
  await withEnhanced(page, stacks, async (stack) => {
    const ai = page.locator('[data-origin="ai"]');
    const first = ai.nth(0);
    const firstId = await first.getAttribute('data-block-id');

    await first.click();
    await page.keyboard.press('ControlOrMeta+Enter');
    await expect(page.locator(`[data-block-id="${firstId}"]`)).toHaveAttribute('data-ai-state', 'accepted');

    const second = ai.nth(1);
    const secondId = await second.getAttribute('data-block-id');
    await second.click();
    await page.keyboard.press('Backspace');
    await expect(page.locator(`[data-block-id="${secondId}"]`)).toHaveCount(0);
    await expect(ai).toHaveCount(3);

    await expect.poll(() => stack.query<{ ai_state: string }>('SELECT ai_state FROM note_blocks WHERE id = ?', firstId!)
      .map((row) => row.ai_state)).toEqual(['accepted']);
    expect(stack.query('SELECT id FROM note_blocks WHERE id = ?', secondId!)).toHaveLength(0);
    expect(stack.query("SELECT id FROM note_blocks WHERE origin = 'user'")).toHaveLength(3);
  });
});

test('AC9_citation - clicking a citation scrolls to and highlights the cited transcript segment', async ({ page, stacks }) => {
  await withEnhanced(page, stacks, async () => {
    const first = page.locator('[data-origin="ai"]').first();

    await first.getByRole('button', { name: /^Source/ }).first().click();

    const cited = page.getByRole('list', { name: 'Transcript' }).locator('[data-segment-id="1002"]');
    await expect(cited).toHaveAttribute('data-highlighted', 'true');
    await expect(cited).toBeInViewport();
    await expect(page.getByRole('list', { name: 'Transcript' }).locator('[data-highlighted="true"]')).toHaveCount(1);
  });
});

test('AC9_reducedMotion - with prefers-reduced-motion the AI entrance does not animate', async ({ page, stacks }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await withEnhanced(page, stacks, async () => {
    const motion = await page.locator('[data-origin="ai"]').first().evaluate((el) => {
      const style = getComputedStyle(el);
      return { animation: style.animationName, transition: style.transitionDuration };
    });

    expect(motion.animation).toBe('none');
    expect(motion.transition).toMatch(/^0s(, 0s)*$/);
  });
});

test('AC9_otherPoints - AI points without a parent are grouped under a label, not stored as a block', async ({ page, stacks }) => {
  await withEnhanced(page, stacks, async (stack) => {
    await expect(page.getByText('Other points')).toBeVisible();
    expect(stack.query("SELECT id FROM note_blocks WHERE origin = 'ai' AND parent_id IS NULL")).toHaveLength(1);
    expect(stack.query("SELECT id FROM note_blocks WHERE plain_text = 'Other points'")).toHaveLength(0);
  });
});
