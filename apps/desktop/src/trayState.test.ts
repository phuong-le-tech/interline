// @vitest-environment jsdom
import { createElement, StrictMode } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import type { CoreEvent, ModelView } from './coreClient';
import type { PermissionKind } from './liveState';

const core = vi.hoisted(() => ({
  invoke: vi.fn().mockResolvedValue(undefined),
  listener: undefined as ((event: CoreEvent) => void) | undefined,
  models: [] as ModelView[],
  download: vi.fn().mockResolvedValue({ started: true }),
}));

vi.mock('@tauri-apps/api/core', () => ({ invoke: core.invoke }));
vi.mock('./coreClient', async (importOriginal) => {
  const original = await importOriginal<typeof import('./coreClient')>();
  return {
    ...original,
    waitForEndpoint: vi.fn().mockResolvedValue({ port: 12345, token: 'test' }),
    CoreClient: class {
      meetings = vi.fn().mockResolvedValue([]);
      uiPreferences = vi.fn().mockResolvedValue({ onboarding_done: true, language: "en" });
      models = vi.fn(async () => core.models);
      downloadModel = core.download;
      subscribe = vi.fn((listener: (event: CoreEvent) => void) => {
        core.listener = listener;
        return new Promise<void>(() => undefined);
      });
    },
  };
});
vi.mock('./Today', () => ({ Today: () => null }));

type TrayState = 'idle' | 'recording' | 'warning';
type Banners = Partial<Record<PermissionKind, string>>;
const states: [string, TrayState][] = [
  ['idle', 'idle'],
  ['starting', 'idle'],
  ['recording', 'recording'],
  ['degraded', 'warning'],
  ['processing', 'idle'],
  ['done', 'idle'],
  ['recovered', 'idle'],
  ['failed', 'idle'],
  ['capture restarting', 'warning'],
  ['stopping', 'idle'],
  ['error', 'idle'],
  ['state unknown', 'idle'],
  ['future-state', 'idle'],
  ['', 'idle'],
];
const bannerCases: [string, Banners][] = [
  ['microphone', { microphone: 'x-apple.systempreferences:microphone' }],
  ['system audio', { system_audio: 'x-apple.systempreferences:system_audio' }],
  ['both permissions', { microphone: 'mic', system_audio: 'system' }],
];

// Load per case so a missing mapping does not prevent the App tests from running.
describe('trayState mapping', () => {
  it.each(states)('maps %j without banners to %s', async (state, expected) => {
    const { trayState } = await vi.importActual<{
      trayState: (state: string, banners: Banners) => TrayState;
    }>('./trayState');
    expect(trayState(state, {})).toBe(expected);
  });

  describe.each(bannerCases)('with %s banners', (_name, banners) => {
    it.each(states)('warns for %j regardless of its normal state %s', async (state) => {
      const { trayState } = await vi.importActual<{
        trayState: (state: string, banners: Banners) => TrayState;
      }>('./trayState');
      expect(trayState(state, banners)).toBe('warning');
    });
  });
});

beforeEach(() => {
  core.invoke.mockClear();
  core.listener = undefined;
  core.models = [];
  core.download.mockClear();
  Object.defineProperty(window, '__TAURI_INTERNALS__', { configurable: true, value: {} });
});

afterEach(() => {
  cleanup();
  Reflect.deleteProperty(window, '__TAURI_INTERNALS__');
});

function trayCalls() {
  return core.invoke.mock.calls.filter(([command]) => command === 'set_tray_state');
}

async function mountApp() {
  const view = render(createElement(App));
  await waitFor(() => expect(core.listener).toBeTypeOf('function'));
  return view;
}

async function emit(name: string, data: CoreEvent['data']) {
  await act(async () => core.listener!({ name, data }));
}

describe('App tray state integration', () => {
  it('automatically resumes one interrupted explicit download, without retrying indefinitely', async () => {
    const model: ModelView = { id: 'small', name: 'Small', kind: 'stt', sizeBytes: 100, state: 'absent', downloadedBytes: 0 };
    core.models = [model];
    await mountApp();
    fireEvent.click(screen.getByRole('button', { name: 'Models' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Download' }));
    await waitFor(() => expect(core.download).toHaveBeenCalledTimes(1));
    await emit('model.download', { ...model, state: 'failed', downloadedBytes: 50, error: 'download interrupted' });
    await waitFor(() => expect(core.download).toHaveBeenCalledTimes(2));
    await emit('model.download', { ...model, state: 'failed', downloadedBytes: 70, error: 'download interrupted' });
    expect(core.download).toHaveBeenCalledTimes(2);
  });

  it.each(['No space left on device', 'checksum mismatch'])('does not automatically retry %s', async (error) => {
    const model: ModelView = { id: 'small', name: 'Small', kind: 'stt', sizeBytes: 100, state: 'absent', downloadedBytes: 0 };
    core.models = [model];
    await mountApp();
    fireEvent.click(screen.getByRole('button', { name: 'Models' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Download' }));
    await waitFor(() => expect(core.download).toHaveBeenCalledTimes(1));
    await emit('model.download', { ...model, state: 'failed', downloadedBytes: 50, error });
    expect(core.download).toHaveBeenCalledTimes(1);
  });

  it('never starts a download from a partial file without an explicit request in this session', async () => {
    const model: ModelView = { id: 'small', name: 'Small', kind: 'stt', sizeBytes: 100, state: 'partial', downloadedBytes: 50 };
    core.models = [model];
    await mountApp();
    await emit('model.download', { ...model, state: 'failed', error: 'download interrupted' });
    expect(core.download).not.toHaveBeenCalled();
  });
  it('initializes the native tray to idle exactly once', async () => {
    await mountApp();
    expect(trayCalls()).toEqual([['set_tray_state', { state: 'idle' }]]);
  });

  it('does not repeat its initial invocation during StrictMode effect replay', async () => {
    render(createElement(StrictMode, null, createElement(App)));
    await waitFor(() => expect(core.listener).toBeTypeOf('function'));
    expect(trayCalls()).toEqual([['set_tray_state', { state: 'idle' }]]);
  });

  it('does not invoke again for repeated recording events or unrelated rerenders', async () => {
    const view = await mountApp();
    await emit('recording.state', { state: 'recording' });
    await emit('recording.state', { state: 'recording' });
    await emit('level', { stream: 'mic', rms: 0.5 });
    await emit('stt.partial', { stream: 'mic', seg: '1', speaker: 'Me', text: 'hello', rev: 1 });
    fireEvent.click(screen.getByRole('button', { name: 'Models' }));
    view.rerender(createElement(App));
    expect(trayCalls()).toEqual([
      ['set_tray_state', { state: 'idle' }],
      ['set_tray_state', { state: 'recording' }],
    ]);
  });

  it('invokes on recording, warning, idle and subsequent recording transitions', async () => {
    await mountApp();
    const expected: TrayState[] = ['idle'];
    for (const [state, tray] of [
      ['recording', 'recording'],
      ['degraded', 'warning'],
      ['idle', 'idle'],
      ['recording', 'recording'],
    ] as const) {
      await emit('recording.state', { state });
      expected.push(tray);
      expect(trayCalls()).toEqual(expected.map((value) => ['set_tray_state', { state: value }]));
    }
  });

  it.each(['microphone', 'system_audio'] as const)('keeps warning while the %s banner exists', async (permission) => {
    await mountApp();
    await emit('recording.state', { state: 'recording' });
    await emit('permission.denied', { permission });
    expect(trayCalls()).toEqual([
      ['set_tray_state', { state: 'idle' }],
      ['set_tray_state', { state: 'recording' }],
      ['set_tray_state', { state: 'warning' }],
    ]);
    await emit('permission.denied', { permission });
    await emit('permission.denied', { permission: permission === 'microphone' ? 'system_audio' : 'microphone' });
    await emit('recording.state', { state: 'idle' });
    await emit('recording.state', { state: 'recording' });
    expect(trayCalls()).toHaveLength(3);
  });

  it('never invokes set_tray_state in a browser, including state and permission changes', async () => {
    Reflect.deleteProperty(window, '__TAURI_INTERNALS__');
    const view = await mountApp();
    for (const state of ['recording', 'degraded', 'idle']) {
      await emit('recording.state', { state });
    }
    await emit('permission.denied', { permission: 'microphone' });
    await emit('permission.denied', { permission: 'system_audio' });
    fireEvent.click(screen.getByRole('button', { name: 'Models' }));
    view.unmount();
    expect(trayCalls()).toEqual([]);
  });
});
