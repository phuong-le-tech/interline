import { test, expect } from './support/notes-stack';

for (const exit of ['stop', 'close'] as const) {
  test(`audio test leaves no meeting behind after ${exit}`, async ({ page, stacks }) => {
    const stack = await stacks({ model: false });
    expect(stack.coreReady, stack.diagnostics()).toBe(true);
    stack.seed(); await stack.putNotes();
    const before = (await stack.api('GET', '/meetings')).json();
    await page.goto('/');
    await page.getByRole('button', { name: 'Settings', exact: true }).click();
    await page.getByRole('button', { name: 'Audio', exact: true }).click();
    await page.getByRole('button', { name: 'Test both inputs', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Continue', exact: true }).click();
    const testRegion = page.getByRole('region', { name: 'Test both inputs', exact: true });
    await expect(testRegion.getByRole('button', { name: 'Stop', exact: true })).toBeEnabled();
    await expect.poll(async () => ((await stack.api('GET', '/meetings')).json() as unknown[]).length).toBe(2);
    if (exit === 'stop') await testRegion.getByRole('button', { name: 'Stop', exact: true }).click();
    else await page.getByRole('button', { name: 'About', exact: true }).click();
    await expect.poll(async () => (await stack.api('GET', '/meetings')).json()).toEqual(before);
    await expect.poll(async () => (await stack.api('GET', '/trash')).json()).toEqual([]);
    expect(stack.query('SELECT id FROM meetings')).toEqual([{ id: stack.meetingId }]);
    await page.getByRole('button', { name: 'Back to meetings', exact: true }).click();
    await expect(page.getByRole('list', { name: 'Meetings', exact: true }).getByRole('listitem')).toHaveCount(1);
  });
}
