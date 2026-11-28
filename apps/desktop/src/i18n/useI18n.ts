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
  return { t, language: current, errorMessage: (error: unknown) => errorMessage(error, current) };
}

const ERROR_KEYS: Record<string, MessageKey> = {
  no_provider: 'error_no_provider', secret_store_unavailable: 'error_secret_store',
  calendar_not_configured: 'error_calendar_missing', calendar_fetch_failed: 'error_calendar_fetch',
  meeting_not_deletable: 'error_meeting_busy', meeting_not_restorable: 'error_meeting_restore',
  invalid_request: 'error_invalid_request', not_found: 'error_not_found',
  model_missing: 'error_model_missing', permission_missing: 'error_permission_missing',
  invalid_output: 'invalid_format', clipboard_failed: 'clipboard_error', notes_save_failed: 'save_failed',
  model_checksum_failed: 'error_model_checksum', model_disk_full: 'error_model_disk',
};

export function errorMessage(error: unknown, locale: Language): string {
  const code = error && typeof error === 'object' && 'code' in error ? error.code : undefined;
  return translate(locale, typeof code === 'string' && Object.hasOwn(ERROR_KEYS, code) ? ERROR_KEYS[code] : 'error_generic');
}

export function stateLabel(state: string, t: ReturnType<typeof useI18n>['t']): string {
  const key = `state_${state.replaceAll(' ', '_')}`;
  return Object.hasOwn(en, key) ? t(key as MessageKey) : t('state_state_unknown');
}
