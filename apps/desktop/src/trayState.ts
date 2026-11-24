import type { PermissionKind } from './liveState';

export function trayState(state: string, banners: Partial<Record<PermissionKind, string>>): 'idle' | 'recording' | 'warning' {
  if (state === 'degraded' || state === 'capture restarting' || Object.keys(banners).length > 0) return 'warning';
  return state === 'recording' ? 'recording' : 'idle';
}
