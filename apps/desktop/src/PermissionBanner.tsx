import { useI18n } from './i18n/useI18n';
import { invoke } from '@tauri-apps/api/core';
import { inTauri } from './coreClient';
import { isSettingsUri, type PermissionKind } from './liveState';

export function PermissionBanner({ kind, settingsUri }: { kind: PermissionKind; settingsUri: string }) {
  const { t } = useI18n();
  const open = (event: { preventDefault: () => void }) => {
    // Inside the app the shell opens the pane; its own allow-list repeats this scheme check.
    if (inTauri() && isSettingsUri(settingsUri)) {
      event.preventDefault();
      void invoke('open_system_settings', { uri: settingsUri });
    }
  };
  return (
    <div role="alert">
      <p>{t(`permission_${kind}`)}</p>
      <a href={settingsUri} onClick={open}>
        {t('open_system_settings')}
      </a>
    </div>
  );
}
