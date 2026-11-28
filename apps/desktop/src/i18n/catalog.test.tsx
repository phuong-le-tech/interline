// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { en } from './en';
import { fr } from './fr';
import { translate } from './useI18n';

describe('UI language catalog', () => {
  it('has identical keys and resolves every key in both languages', () => {
    expect(Object.keys(fr).sort()).toEqual(Object.keys(en).sort());
    for (const key of Object.keys(en) as (keyof typeof en)[]) {
      for (const language of ['en', 'fr'] as const) {
        expect(translate(language, key)).toBeTruthy();
        expect(translate(language, key)).not.toBe(key);
      }
    }
  });
});
