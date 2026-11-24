import { expect, test } from '@playwright/test';
import { withM1Stack } from './support/m1-stack';

test('calendar denial shows neutral settings banner and manual meetings remain usable', async ({ page }, info) => {
  await withM1Stack({ agentArgs: ['--deny=calendar'] }, async (stack) => {
    expect(stack.coreReady, stack.diagnostics()).toBe(true);
    expect(stack.viteReady, stack.diagnostics()).toBe(true);
    await page.goto('/');
    const banner = page.getByRole('alert').filter({ hasText: 'Calendar access denied - using manual meetings' });
    await expect.soft(banner).toBeVisible();
    await expect.soft(banner.getByRole('link', { name: 'Open System Settings', exact: true }))
      .toHaveAttribute('href', 'x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars');
    await expect(page.getByRole('alert').filter({ hasText: "I can't hear" })).toHaveCount(0);
    await page.getByRole('textbox', { name: 'Meeting title', exact: true }).fill('Manual after calendar denial');
    const created = page.waitForResponse((response) =>
      response.url().endsWith('/api/v1/meetings') && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'New meeting', exact: true }).click();
    expect((await created).status()).toBe(201);
    await expect(page.getByRole('textbox', { name: 'Notes', exact: true })).toBeVisible();
  }, async (body) => { await info.attach('calendar-stack', { body, contentType: 'text/plain' }); });
});
