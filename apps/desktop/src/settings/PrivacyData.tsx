import { useCallback, useEffect, useRef, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { inTauri, type CoreClient } from '../coreClient';
import { Retention } from './Retention';
import { Trash } from './Trash';
import { Privacy } from './Privacy';
import { UpdateCheck } from './UpdateCheck';
import { FileVaultNotice, type FileVaultState } from './FileVaultNotice';

export function PrivacyData({ client, onChanged }: { client: CoreClient; onChanged: () => void }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const [fileVault, setFileVault] = useState<FileVaultState>('unknown');
  useEffect(() => {
    heading.current?.focus();
    if (inTauri()) void invoke<FileVaultState>('filevault_status').then(setFileVault).catch(() => setFileVault('unknown'));
  }, []);
  const retention = useCallback(() => client.retention(), [client]);
  const trash = useCallback(() => client.trash(), [client]);
  const privacy = useCallback(() => client.privacy(), [client]);
  return <section aria-label="Privacy and data">
    <h2 ref={heading} tabIndex={-1}>Privacy and data</h2>
    <FileVaultNotice state={fileVault} />
    <Retention load={retention} save={(value) => client.setRetention(value)} />
    <Trash load={trash} restore={(id) => client.restoreMeeting(id)} erase={(id) => client.eraseMeeting(id)} onChanged={onChanged} />
    <Privacy load={privacy} />
    {inTauri() && <UpdateCheck check={() => invoke<string | null>('check_update')} install={() => invoke<void>('install_update')} />}
  </section>;
}
