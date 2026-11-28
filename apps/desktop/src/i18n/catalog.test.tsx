// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { en } from './en';
import { fr } from './fr';
import { translate } from './useI18n';
import ts from 'typescript';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

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
  it('rejects an unknown key rather than rendering it', () => {
    expect(() => translate('fr', 'missing_key' as keyof typeof en)).toThrow('Unknown UI message');
  });
  it('keeps component text and accessible labels in the typed catalog', () => {
    const violations: string[] = [];
    function scan(directory: string) {
      for (const file of readdirSync(directory, { withFileTypes: true })) {
        const path = join(directory, file.name);
        if (file.isDirectory()) { scan(path); continue; }
        if (!file.name.endsWith('.tsx') || file.name.includes('.test.')) continue;
        const source = ts.createSourceFile(path, readFileSync(path, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
        const visit = (node: ts.Node) => {
          if (ts.isJsxText(node) && /[A-Za-z]/.test(node.text) && !['Interline', 'Ollama', 'Anthropic'].includes(node.text.trim())) violations.push(`${file.name}: ${node.text.trim()}`);
          if (ts.isJsxAttribute(node) && ['aria-label', 'placeholder', 'title', 'label'].includes(node.name.getText(source))
            && node.initializer && ts.isStringLiteral(node.initializer)) violations.push(`${file.name}: ${node.initializer.text}`);
          ts.forEachChild(node, visit);
        };
        visit(source);
      }
    }
    scan(join(import.meta.dirname, '..'));
    expect(violations).toEqual([]);
  });
});
