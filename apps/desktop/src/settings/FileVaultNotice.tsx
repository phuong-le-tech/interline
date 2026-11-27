export type FileVaultState = 'on' | 'off' | 'unknown';

export function FileVaultNotice({ state }: { state: FileVaultState }) {
  if (state === 'on') return null;
  return <p role="status" style={{ borderInlineStart: '2px solid var(--ochre, #A87A2C)', paddingInlineStart: '0.5rem' }}><strong>FileVault {state === 'off' ? 'is off' : 'status is unknown'}.</strong>{' '}
    Interline relies on disk encryption to protect stored audio, transcripts and notes. Check FileVault in System Settings. You can keep using Interline.
  </p>;
}
