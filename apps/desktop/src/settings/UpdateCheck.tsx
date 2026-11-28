import { useState } from 'react';

type Phase = { kind: 'idle' } | { kind: 'checking' } | { kind: 'current' } | { kind: 'found'; version: string } | { kind: 'installing' } | { kind: 'error'; message: string };

/** Nothing is requested until the first click; installing needs a second, separate click. */
export function UpdateCheck({ check, install }: { check: () => Promise<string | null>; install: () => Promise<void> }) {
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const fail = (e: unknown) => setPhase({ kind: 'error', message: String(e) });
  const runCheck = () => {
    setPhase({ kind: 'checking' });
    check().then((version) => setPhase(version ? { kind: 'found', version } : { kind: 'current' })).catch(fail);
  };
  const runInstall = () => {
    setPhase({ kind: 'installing' });
    install().catch(fail);
  };
  return <section aria-label="Updates"><h3>Updates</h3>
    <p>Interline never checks for updates by itself. Checking contacts github.com only when you click.</p>
    <button type="button" onClick={runCheck} disabled={phase.kind === 'checking' || phase.kind === 'installing'}>Check for updates</button>
    <p role="status">
      {phase.kind === 'checking' && 'Checking...'}
      {phase.kind === 'current' && 'Interline is up to date.'}
      {phase.kind === 'found' && `Version ${phase.version} is available.`}
      {phase.kind === 'installing' && 'Downloading and installing. Interline will restart.'}
    </p>
    {phase.kind === 'found' && <button type="button" onClick={runInstall}>Install and restart</button>}
    {phase.kind === 'error' && <p role="alert">{phase.message}</p>}
  </section>;
}
