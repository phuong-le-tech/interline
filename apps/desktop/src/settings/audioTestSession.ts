import type { CoreClient } from '../coreClient';

/** Owns only the disposable meeting created for an input test, including an in-flight start. */
export class AudioTestSession {
  private starting?: Promise<void>;
  private meetingId?: string;
  private closing?: Promise<string | undefined>;

  constructor(private readonly client: CoreClient) {}

  start(title: string): Promise<void> {
    this.starting ??= (async () => {
      const { id } = await this.client.createMeeting(title);
      this.meetingId = id;
      await this.client.acknowledgeConsent(id);
      await this.client.startRecording(id);
    })();
    return this.starting;
  }

  dispose(): Promise<string | undefined> {
    if (!this.starting && !this.meetingId) return Promise.resolve(undefined);
    this.closing ??= (async () => {
      await this.starting?.catch(() => undefined);
      const id = this.meetingId;
      if (!id) { this.starting = undefined; return; }
      let stopRequested = false;
      for (let attempt = 0; ; attempt++) {
        const meeting = (await this.client.meetings()).find((value) => value.id === id);
        if (!meeting || !['recording', 'degraded', 'processing'].includes(meeting.status)) break;
        if (attempt === 100) throw new Error('Audio test has not finished stopping');
        if (meeting.status !== 'processing' && !stopRequested) {
          await this.client.stopRecording();
          stopRequested = true;
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
      try {
        await this.client.deleteMeeting(id);
        await this.client.eraseMeeting(id);
      } catch (error) {
        if (!error || typeof error !== 'object' || !('code' in error) || error.code !== 'not_found') throw error;
      }
      this.meetingId = undefined;
      this.starting = undefined;
      return id;
    })().finally(() => { this.closing = undefined; });
    return this.closing;
  }
}
