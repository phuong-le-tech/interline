import { useCallback, useEffect, useMemo, useState } from 'react';
import { CoreClient, waitForEndpoint, type CoreEvent, type Meeting, type TranscriptLine } from './coreClient';

const SLOW_START_MS = 1500;
const ACTIVE_STATES = new Set(['recording', 'degraded', 'processing']);

export function App() {
  const [client, setClient] = useState<CoreClient>();
  const [slowStart, setSlowStart] = useState(false);
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [lines, setLines] = useState<TranscriptLine[]>([]);
  const [state, setState] = useState('idle');
  const [error, setError] = useState<string>();

  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => setSlowStart(true), SLOW_START_MS);
    void waitForEndpoint(controller.signal).then((endpoint) => {
      clearTimeout(timer);
      if (endpoint) setClient(new CoreClient(endpoint));
    });
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, []);

  const refreshMeetings = useCallback(async (core: CoreClient) => {
    setMeetings(await core.meetings());
  }, []);

  useEffect(() => {
    if (!client) return;
    const controller = new AbortController();
    const onEvent = (event: CoreEvent) => {
      if (event.name === 'recording.state') {
        setState(String(event.data.state));
        void refreshMeetings(client);
      } else if (event.name === 'stt.final') {
        const line = event.data as unknown as TranscriptLine;
        setLines((current) =>
          current.some((l) => l.stream === line.stream && l.seg === line.seg) ? current : [...current, line],
        );
      } else if (event.name === 'recording.error' || event.name === 'capture.unavailable') {
        setError(`${String(event.data.code)}: ${String(event.data.message ?? '')}`);
      }
    };
    void client.subscribe(onEvent, controller.signal);
    void refreshMeetings(client).catch((e: unknown) => setError(String(e)));
    return () => controller.abort();
  }, [client, refreshMeetings]);

  const recording = useMemo(() => ACTIVE_STATES.has(state), [state]);

  const record = async () => {
    if (!client) return;
    setError(undefined);
    setLines([]);
    try {
      await client.startRecording();
      await refreshMeetings(client);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const stop = async () => {
    if (!client) return;
    try {
      await client.stopRecording();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <main>
      <h1>Interline</h1>
      {!client && slowStart && <p>Starting Interline...</p>}
      <div>
        <button type="button" onClick={record} disabled={!client || recording}>Record</button>
        <button type="button" onClick={stop} disabled={!client || state !== 'recording'}>Stop</button>
      </div>
      <p role="status">{state}</p>
      {error && <p role="alert">{error}</p>}
      <h2>Transcript</h2>
      <ul aria-label="Transcript">
        {lines.map((line) => (
          <li key={`${line.stream}:${line.seg}`}>
            <strong>{line.speaker}</strong> {line.text}
          </li>
        ))}
      </ul>
      <h2>Meetings</h2>
      <ul aria-label="Meetings">
        {meetings.map((meeting) => (
          <li key={meeting.id}>
            {meeting.title || meeting.id.slice(0, 8)} - {meeting.status}
          </li>
        ))}
      </ul>
    </main>
  );
}
