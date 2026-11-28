import { useI18n } from '../i18n/useI18n';
export type FileVaultState = 'on' | 'off' | 'unknown';

export function FileVaultNotice({ state }: { state: FileVaultState }) {
  const { t } = useI18n();
  if (state === 'on') return null;
  return <p role="status" style={{ borderInlineStart: '2px solid var(--ochre)', paddingInlineStart: '0.5rem' }}><strong>{t(state === 'off' ? 'filevault_off' : 'filevault_unknown')}</strong>{' '}
    {t('filevault_description')}
  </p>;
}
