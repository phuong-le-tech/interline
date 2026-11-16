import { invoke } from '@tauri-apps/api/core';
import { inTauri } from './coreClient';
import { isSettingsUri, type PermissionKind } from './liveState';

const BANNER_TEXT: Record<PermissionKind, string> = {
  system_audio:
    "I can't hear the other participants. Interline needs System Audio Recording permission to capture them.",
  microphone: "I can't hear you. Interline needs Microphone permission to capture your voice.",
};

export function PermissionBanner({ kind, settingsUri }: { kind: PermissionKind; settingsUri: string }) {
  const open = (event: { preventDefault: () => void }) => {
    // Inside the app the shell opens the pane; its own allow-list repeats this scheme check.
    if (inTauri() && isSettingsUri(settingsUri)) {
      event.preventDefault();
      void invoke('open_system_settings', { uri: settingsUri });
    }
  };
  return (
    <div role="alert">
      <p>{BANNER_TEXT[kind]}</p>
      <a href={settingsUri} onClick={open}>
        Open System Settings
      </a>
    </div>
  );
}
