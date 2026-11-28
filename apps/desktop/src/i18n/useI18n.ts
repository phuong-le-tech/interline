import { useMemo, useSyncExternalStore } from 'react';
import { en } from './en';
import { fr } from './fr';

export type Language = 'en' | 'fr';
export type MessageKey = keyof typeof en;
export const detectLanguage = (locale: string): Language => locale.toLowerCase().startsWith('fr') ? 'fr' : 'en';
let language: Language = detectLanguage(navigator.language);
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };

export function setLanguage(value: Language) {
  language = value;
  document.documentElement.lang = value;
  listeners.forEach((listener) => listener());
}

export function translate(locale: Language, key: MessageKey, values: Record<string, string | number> = {}): string {
  const message: string = (locale === 'fr' ? fr : en)[key];
  if (message === undefined) throw new Error(`Unknown UI message: ${key}`);
  return message.replace(/\{(\w+)\}/g, (match, name: string) => values[name] === undefined ? match : String(values[name]));
}

export function useI18n() {
  const current = useSyncExternalStore(subscribe, () => language);
  const t = useMemo(() => (key: MessageKey, values?: Record<string, string | number>) => translate(current, key, values), [current]);
  return { t, language: current };
}

export function stateLabel(state: string, t: ReturnType<typeof useI18n>['t']): string {
  const key = `state_${state.replaceAll(' ', '_')}`;
  return Object.hasOwn(en, key) ? t(key as MessageKey) : t('state_state_unknown');
}
