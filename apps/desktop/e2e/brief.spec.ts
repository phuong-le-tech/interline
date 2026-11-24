import { expect, test } from './support/notes-stack';

test('AI brief is explicit, cached with citations, and stale after an action changes', async ({ page, stacks }) => {
  const stack = await stacks();
  expect(stack.viteReady, stack.diagnostics()).toBe(true);
  expect(stack.coreReady, stack.diagnostics()).toBe(true);
  stack.seed();
  await stack.putNotes();
  const created = await stack.api('POST', '/meetings', { title: stack.meeting.title });
  expect(created.status).toBe(201);
  const next = (created.json() as { id: string }).id;
  const open = async () => {
    await page.goto('/');
    await page.getByRole('list', { name: 'Meetings' }).getByRole('listitem').filter({ hasText: stack.meeting.title })
      .first().getByRole('button', { name: 'Open notes' }).click();
    await page.getByText(/^Brief:/).click();
  };
  await open();
  const brief = page.getByLabel('Brief', { exact: true });
  await expect(brief.getByRole('button', { name: 'Write brief' })).toBeDisabled();
  await expect(brief).toContainText('Configure a provider');
  expect(stack.model!.requests).toHaveLength(0);

  await stack.configureProvider();
  stack.model!.reply = JSON.stringify({ lines: [{ text: 'Review the previous decisions.', sources: [stack.meetingId] }] });
  await open();
  await brief.getByRole('button', { name: 'Write brief' }).click();
  await expect(brief.getByText('Review the previous decisions.')).toBeVisible();
  await expect(brief.getByRole('button', { name: `Source: ${stack.meeting.title}` })).toBeVisible();
  const calls = () => stack.model!.requests.filter((r) => r.path.endsWith('/chat/completions'));
  expect(calls()).toHaveLength(1);
  await open();
  await expect(brief.getByText('Review the previous decisions.')).toBeVisible();
  expect(calls()).toHaveLength(1);
  const cached = await stack.api('POST', `/meetings/${next}/brief/ai`, {});
  expect(cached.status).toBe(200);
  expect(calls()).toHaveLength(1);

  // Existing enhancement fixture creates a real open action without a second database writer.
  stack.model!.reply = stack.reply;
  expect((await stack.api('POST', `/meetings/${stack.meetingId}/enhance`, {})).status).toBe(200);
  const beforeRefresh = calls().length;
  await open();
  await expect(brief.getByText(/stale/i)).toBeVisible();
  await expect(brief.getByText('Review the previous decisions.')).toHaveCount(0);
  expect(calls()).toHaveLength(beforeRefresh);
  stack.model!.reply = JSON.stringify({ lines: [{ text: 'Follow up the open actions.', sources: [stack.meetingId] }] });
  await brief.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(brief.getByText('Follow up the open actions.')).toBeVisible();
  expect(calls()).toHaveLength(beforeRefresh + 1);
  expect(stack.query("SELECT plain_text FROM note_blocks WHERE meeting_id = ? AND origin = 'user' ORDER BY position", stack.meetingId)
    .map((row) => row.plain_text)).toEqual(stack.meeting.notes.map((note) => note.text));
});
