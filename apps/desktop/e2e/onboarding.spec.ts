import { test, expect } from './support/notes-stack';
import type { Page } from '@playwright/test';

async function tabTo(page: Page, name: string) {
  const button = page.getByRole('button', { name, exact: true });
  for (let count = 0; count < 60; count++) {
    if (await button.evaluate((element) => element === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  await expect(button).toBeFocused();
}

async function checkControls(page: Page) {
  for (const control of await page.locator('button, input:not([type="checkbox"]):not([type="radio"]), select, a, summary').all()) {
    if (!await control.isVisible()) continue;
    await expect(control).toHaveAccessibleName(/.+/);
    const box = await control.boundingBox();
    expect(box!.height).toBeGreaterThanOrEqual(44);
  }
  const contrasts = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement);
    const luminance = (token: string) => {
      const color = style.getPropertyValue(token).trim();
      const channels = [1, 3, 5].map((offset) => parseInt(color.slice(offset, offset + 2), 16) / 255)
        .map((value) => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
      return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
    };
    return ['--bg', '--surface'].flatMap((background) => ['--fg', '--muted'].map((foreground) =>
      (luminance(background) + .05) / (luminance(foreground) + .05)));
  });
  for (const ratio of contrasts) expect(ratio).toBeGreaterThanOrEqual(4.5);
}

test('fresh onboarding is keyboard usable, optional, and persisted with language across core restart', async ({ page, stacks }) => {
  const stack = await stacks({ model: false, onboarding: true });
  expect(stack.coreReady, stack.diagnostics()).toBe(true);
  await page.goto('/');
  const onboarding = page.getByRole('region', { name: 'Welcome to Interline' });
  await expect(onboarding).toBeVisible();
  await checkControls(page);
  await tabTo(page, 'Continue');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Permissions', exact: true })).toBeVisible();
  await tabTo(page, 'Continue without');
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Transcription', exact: true })).toBeVisible();
  await tabTo(page, 'Continue without');
  await page.keyboard.press('Enter');
  await tabTo(page, 'Later');
  await expect(page.getByRole('button', { name: 'Later', exact: true })).toHaveCSS('outline-style', 'solid');
  await page.keyboard.press('Enter');
  await expect(onboarding).toBeHidden();
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await checkControls(page);
  await page.getByLabel('Language', { exact: true }).selectOption('fr');
  await expect(page.getByRole('heading', { name: 'Réglages', exact: true })).toBeVisible();
  await stack.killCore();
  expect(await stack.restartCore()).toBe(true);
  await page.reload();
  await expect(onboarding).toBeHidden();
  await expect(page.getByRole('button', { name: 'Réglages', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Réglages', exact: true }).click();
  await expect(page.getByLabel('Langue', { exact: true })).toHaveValue('fr');
  await page.getByRole('button', { name: 'À propos', exact: true }).click();
  await expect(page.getByText('Licence MIT', { exact: true })).toBeVisible();
  await page.keyboard.press('Control+k');
  await expect(page.getByRole('dialog', { name: 'Rechercher', exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
});

test('denied permissions from MockAgent leave a keyboard Continue without path', async ({ page, stacks }) => {
  const stack = await stacks({ model: false, onboarding: true, denySystemAudio: true });
  expect(stack.coreReady, stack.diagnostics()).toBe(true);
  await page.goto('/');
  await tabTo(page, 'Continue'); await page.keyboard.press('Enter');
  await expect(page.getByRole('link', { name: 'Open System Settings' })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText("I can't hear the other participants");
  await tabTo(page, 'Continue without'); await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Transcription', exact: true })).toBeVisible();
});

test('all settings sections are keyboard reachable and language changes home, notes and settings', async ({ page, stacks }) => {
  const stack = await stacks({ model: false });
  expect(stack.coreReady, stack.diagnostics()).toBe(true);
  stack.seed(); await stack.putNotes();
  await page.goto('/');
  await tabTo(page, 'Settings'); await page.keyboard.press('Enter');
  for (const name of ['Audio', 'Transcription', 'AI', 'Calendar', 'Privacy', 'Storage', 'About', 'General']) {
    await tabTo(page, name); await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name, exact: true })).toHaveAttribute('aria-current', 'page');
  }
  await page.getByLabel('Language', { exact: true }).selectOption('fr');
  await expect(page.getByRole('heading', { name: 'Réglages', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Retour aux réunions', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Aujourd’hui', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Nouvelle réunion', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Ouvrir les notes', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Enrichir', exact: true })).toBeVisible();
  await expect(page.getByRole('radio', { name: 'Mes notes', exact: true })).toBeVisible();
  await expect(page.getByRole('textbox', { name: 'Notes', exact: true })).toContainText(stack.meeting.notes[0].text);
  await page.keyboard.press('Control+k');
  await expect(page.getByRole('combobox', { name: 'Rechercher dans les notes et la transcription' })).toBeFocused();
  await page.keyboard.press('Shift+Tab');
  await expect(page.getByRole('button', { name: 'Fermer', exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).toBeHidden();
});
