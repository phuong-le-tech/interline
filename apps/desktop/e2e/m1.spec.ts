import { expect, test, type Page } from '@playwright/test';
import { withM1Stack } from './support/m1-stack';

type Stack = Parameters<Parameters<typeof withM1Stack>[1]>[0];

async function openDesktop(page: Page, stack: Stack) {
  expect(stack.coreReady, stack.diagnostics()).toBe(true);
  expect(stack.viteReady, stack.diagnostics()).toBe(true);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Interline', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Record', exact: true })).toBeEnabled();
}

for (const fault of ['--deny=system_audio', '--silent-system']) {
  test(`AC6_silent - ${fault} shows the silent system banner within 10 seconds`, async ({ page }, info) => {
    await withM1Stack({ agentArgs: ['--speed=1', fault] }, async (stack) => {
      await openDesktop(page, stack);
      const started = Date.now();
      await page.getByRole('button', { name: 'Record', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: 'Continue', exact: true }).click();
      // A generic permission error is not the actionable, stream-specific banner.
      const banner = page.getByRole('alert').filter({ hasText: "I can't hear the other participants" });
      await expect(banner).toBeVisible({ timeout: Math.max(1, 9_500 - (Date.now() - started)) });
      await expect(banner.getByRole('link', { name: /settings|permission|fix/i })).toBeVisible();
      expect(Date.now() - started).toBeLessThan(10_000);
    }, async (body) => { await info.attach('m1-stack', { body, contentType: 'text/plain' }); });
  });
}

test('AC12_live - accessible Me and Them meters both vary during recording', async ({ page }, info) => {
  await withM1Stack(undefined, async (stack) => {
    await openDesktop(page, stack);
    await page.getByRole('button', { name: 'Record', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Continue', exact: true }).click();
    const meters = ['Me', 'Them'].map((name) => page.getByRole('meter', { name, exact: true }));
    // Check both controls even if the first one is missing.
    await Promise.all(meters.map((meter) => expect(meter).toBeVisible()));
    await Promise.all(meters.map(async (meter) => {
      const values = new Set<number>();
      await expect.poll(async () => {
        const raw = await meter.getAttribute('aria-valuenow');
        const value = raw === null ? NaN : Number(raw);
        if (Number.isFinite(value)) values.add(value);
        return values.size;
      }, { message: 'Each stream meter must expose changing numeric levels, not a static placeholder',
        intervals: [50, 100, 150], timeout: 5_000 }).toBeGreaterThan(1);
      expect([...values].some((value) => value > 0)).toBe(true);
    }));
  }, async (body) => { await info.attach('m1-stack', { body, contentType: 'text/plain' }); });
});

test('AC12_live - the same partial transcript row becomes final without duplication', async ({ page }, info) => {
  await withM1Stack({ agentArgs: ['--speed=1'] }, async (stack) => {
    await openDesktop(page, stack);
    await page.getByRole('button', { name: 'Record', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Continue', exact: true }).click();
    const transcript = page.getByRole('list', { name: 'Transcript', exact: true });
    // aria-busy communicates the partial state without relying on a CSS class.
    const partial = transcript.getByRole('listitem').filter({ hasText: /\bMe\b/ })
      .and(transcript.locator('[aria-busy="true"]')).first();
    await expect(partial, 'A real stt.partial must appear before the final').toBeVisible();
    const row = await partial.elementHandle();
    expect(row).not.toBeNull();
    try {
      await expect.poll(async () => row!.evaluate((node) =>
        node.isConnected && node.getAttribute('aria-busy') === 'false'),
      { message: 'The existing partial row must become final, not be removed and appended', timeout: 10_000 })
        .toBe(true);
      const finalText = (await row!.innerText()).trim();
      expect(finalText.replace(/^Me\s*/, '').length).toBeGreaterThan(0);
      const matchingRows = transcript.getByRole('listitem').filter({ hasText: finalText });
      await expect(matchingRows).toHaveCount(1);
      expect(await matchingRows.evaluate((node, original) => node === original, row!)).toBe(true);
    } finally { await row?.dispose(); }
  }, async (body) => { await info.attach('m1-stack', { body, contentType: 'text/plain' }); });
});

test('AC12_live - a real MockAgent crash shows Capture restarting and recovers', async ({ page }, info) => {
  await withM1Stack({ agentArgs: ['--speed=1', '--crash-at=3s'] }, async (stack) => {
    await openDesktop(page, stack);
    await page.getByRole('button', { name: 'Record', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Continue', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('recording');
    // --crash-at exits the real child with code 13. No routed or injected SSE.
    await expect.soft(page.getByRole('alert').filter({ hasText: 'Capture restarting' }))
      .toBeVisible({ timeout: 8_000 });
    await expect.poll(() => stack.diagnostics(), {
      message: 'The real agent must have exited before recovery',
      timeout: 8_000,
    }).toContain('capture agent down (agent_exited)');
    await expect(page.getByRole('status')).toHaveText('recording');
    const transcript = page.getByRole('list', { name: 'Transcript', exact: true });
    await expect(transcript.getByRole('listitem').filter({ hasText: /\bMe\b/ }).first()).toBeVisible();
    await expect(transcript.getByRole('listitem').filter({ hasText: /\bThem\b/ }).first()).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: 'Capture restarting' })).toBeHidden();
    await page.getByRole('button', { name: 'Stop', exact: true }).click();
    await expect(page.getByRole('status')).toHaveText('done');
  }, async (body) => { await info.attach('m1-stack', { body, contentType: 'text/plain' }); });
});

test('AC12_live - explicit model download reports progress then a checksum error', async ({ page }, info) => {
  await withM1Stack({ modelDownload: true }, async (stack) => {
    await openDesktop(page, stack);
    await expect(page.getByRole('button', { name: 'Models', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Models', exact: true }).click();
    const model = page.getByRole('listitem').filter({ hasText: 'M1 checksum fixture' });
    await expect(model).toBeVisible();
    expect('fixtureRequests' in stack ? stack.fixtureRequests : []).toEqual([]);
    await model.getByRole('button', { name: 'Download', exact: true }).click();
    const progress = model.getByRole('progressbar', { name: /download/i });
    await expect(progress).toBeVisible();
    let initial = 0;
    await expect.poll(async () => {
      initial = Number(await progress.getAttribute('aria-valuenow'));
      return initial;
    }).toBeGreaterThan(0);
    await expect.poll(async () => Number(await progress.getAttribute('aria-valuenow'))).toBeGreaterThan(initial);
    await expect(model.getByRole('alert')).toContainText(/checksum|sha-?256/i);
    await expect(model.getByRole('button', { name: /retry/i })).toBeEnabled();
    await expect(model.getByText('ready', { exact: true })).toHaveCount(0);
    expect('fixtureRequests' in stack ? stack.fixtureRequests : [])
      .toContain('GET /ggml-m1-checksum.bin');
  }, async (body) => { await info.attach('m1-stack', { body, contentType: 'text/plain' }); });
});
