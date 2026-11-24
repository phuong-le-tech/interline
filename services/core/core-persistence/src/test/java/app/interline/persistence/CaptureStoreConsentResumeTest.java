package app.interline.persistence;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureStoreConsentResumeTest {
    @TempDir Path data;

    @ParameterizedTest
    @ValueSource(strings = {"done", "failed", "recovered"})
    void reopeningACompletedCaptureImmediatelyRestoresRecordingState(String status) {
        try (var database = SqliteDatabase.open(data.resolve("db.sqlite"))) {
            var capture = new CaptureStore(database);
            capture.createMeeting("meeting", 100);
            new MeetingStore(database).acknowledgeConsent("meeting", 150);
            database.write(db -> db.sql("UPDATE meetings SET status = ?, ended_at = 200 WHERE id = 'meeting'")
                    .param(status).update());

            capture.createMeeting("meeting", 300);

            var reopened = capture.findMeeting("meeting").orElseThrow();
            assertThat(reopened.status()).isEqualTo("recording");
            assertThat(reopened.endedAt()).isNull();
            assertThat(reopened.startedAt()).isEqualTo(100);
            assertThat(reopened.consentAckAt()).isEqualTo(150);
            assertThat(capture.runCount("meeting")).isEqualTo(1);
            Long speakers = database.read(db -> db.sql("SELECT count(*) FROM speakers WHERE meeting_id = 'meeting'")
                    .query(Long.class).single());
            assertThat(speakers).isEqualTo(2);
        }
    }
}
