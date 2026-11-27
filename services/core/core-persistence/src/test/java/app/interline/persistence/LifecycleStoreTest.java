package app.interline.persistence;

import app.interline.domain.MeetingStatus;
import app.interline.domain.StreamKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.*;

class LifecycleStoreTest {
    @TempDir Path data;
    static final long DAY = 86_400_000L;

    @Test void retentionIsPinnedAtEndAndOnlyAudioIsPurged() throws Exception {
        try (var db = SqliteDatabase.open(data.resolve("db"))) {
            var settings = new SettingsStore(db);
            var capture = new CaptureStore(db);
            var audio = new AudioStore(data.resolve("audio"));
            var lifecycle = new LifecycleStore(db, data);
            assertThat(settings.audioRetention()).isEqualTo("7d");
            for (String value : new String[]{"after_transcription", "7d", "30d", "forever"}) {
                settings.setAudioRetention(value, 0);
                capture.createMeeting(value, 0);
                audio.write(value, StreamKind.MIC, 0, new byte[2], new HashSet<>());
                capture.insertFinal(value, StreamKind.MIC, "s", 0, 16, "original transcript", "test", null);
                db.write(c -> c.sql("INSERT INTO note_blocks(id,meeting_id,position,kind,origin,content,plain_text,created_at,updated_at) VALUES (?,?,'a','paragraph','user','original notes','original notes',0,0)").params(value,value).update());
                capture.setStatus(value, MeetingStatus.DONE, DAY);
            }
            lifecycle.purgeAudio(DAY);
            assertThat(Files.exists(data.resolve("audio/after_transcription"))).isFalse();
            assertThat(Files.exists(data.resolve("audio/7d"))).isTrue();
            lifecycle.purgeAudio(8 * DAY);
            lifecycle.purgeAudio(8 * DAY);
            assertThat(Files.exists(data.resolve("audio/7d"))).isFalse();
            assertThat(Files.exists(data.resolve("audio/30d"))).isTrue();
            lifecycle.purgeAudio(31 * DAY);
            assertThat(Files.exists(data.resolve("audio/30d"))).isFalse();
            assertThat(Files.exists(data.resolve("audio/forever"))).isTrue();
            assertThat(db.<Integer>read(c -> c.sql("SELECT count(*) FROM transcript_segments").query(Integer.class).single())).isEqualTo(4);
            assertThat(db.<Integer>read(c -> c.sql("SELECT count(*) FROM note_blocks WHERE plain_text = 'original notes'").query(Integer.class).single())).isEqualTo(4);
            assertThat(db.<Integer>read(c -> c.sql("SELECT count(*) FROM meetings WHERE audio_dir IS NOT NULL").query(Integer.class).single())).isEqualTo(1);
        }
    }

    @Test void unconfirmedTranscriptionKeepsAudioAndResumingCancelsTheDeadline() throws Exception {
        try (var db = SqliteDatabase.open(data.resolve("db"))) {
            new SettingsStore(db).setAudioRetention("after_transcription",0);
            var capture = new CaptureStore(db);
            var lifecycle = new LifecycleStore(db,data);
            capture.createMeeting("a",0);
            Files.createDirectories(data.resolve("audio/a"));
            Files.writeString(data.resolve("audio/a/part.pcm"),"test");
            capture.setStatus("a",MeetingStatus.DONE,1,false);
            lifecycle.purgeAudio(DAY);
            assertThat(Files.exists(data.resolve("audio/a/part.pcm"))).isTrue();
            capture.createMeeting("a",2);
            capture.setStatus("a",MeetingStatus.DONE,3,true);
            capture.createMeeting("a",4);
            lifecycle.purgeAudio(DAY);
            assertThat(Files.exists(data.resolve("audio/a/part.pcm"))).isTrue();
            assertThat(lifecycle.trash("a",5)).isFalse();
        }
    }

    @Test void trashRestoresAndHardDeleteCascadesOnlyItsOwnRowsAndFiles() throws Exception {
        try (var db = SqliteDatabase.open(data.resolve("db"))) {
            var capture = new CaptureStore(db);
            var lifecycle = new LifecycleStore(db, data);
            for (String id : new String[]{"a", "b"}) {
                capture.createMeeting(id, 0);
                capture.insertFinal(id, StreamKind.MIC, "s", 0, 16, "unique transcript", "test", null);
                capture.setStatus(id, MeetingStatus.DONE, 1);
                Files.createDirectories(data.resolve("audio/" + id));
                Files.writeString(data.resolve("audio/" + id + "/part.pcm"), "test");
                Files.createDirectories(data.resolve("recovery/" + id));
                Files.writeString(data.resolve("recovery/" + id + "/part.wav"), "recovered");
                Files.createDirectories(data.resolve("spool/" + id));
                Files.writeString(data.resolve("spool/" + id + "/part.pcm"), "rescue");
                db.write(c -> {
                    c.sql("INSERT INTO ai_runs(id,meeting_id,kind,model,prompt_version,status,started_at) VALUES (?,?,'brief','test','1','ok',0)").params(id,id).update();
                    c.sql("INSERT INTO briefs(id,meeting_id,text,input_hash,ai_run_id,created_at) VALUES (?,?,'[]','hash',?,0)").params(id,id,id).update();
                    c.sql("INSERT INTO note_blocks(id,meeting_id,position,kind,origin,content,plain_text,created_at,updated_at) VALUES (?,?,'a','paragraph','user','unique notes','unique notes',0,0)").params(id,id).update();
                    c.sql("INSERT INTO action_items(id,meeting_id,text,created_at) VALUES (?,?,'action',0)").params(id,id).update();
                    c.sql("INSERT INTO people(id,display_name,created_at) VALUES (?, 'Person',0)").param(id).update();
                    return c.sql("INSERT INTO meeting_participants VALUES (?,?)").params(id,id).update();
                });
            }
            lifecycle.trash("a", 10);
            assertThat(capture.findMeeting("a")).isEmpty();
            assertThat(lifecycle.trash()).extracting(LifecycleStore.Trashed::id).containsExactly("a");
            assertThat(lifecycle.restore("a", 20)).isTrue();
            assertThat(capture.findMeeting("a")).isPresent();
            lifecycle.trash("a", 30);
            lifecycle.purgeTrash(30 * DAY + 30);
            assertThat(lifecycle.trash()).hasSize(1);
            lifecycle.purgeTrash(30 * DAY + 31);
            lifecycle.hardDelete("a");
            for (String table : new String[]{"meetings", "capture_runs", "speakers", "transcript_segments", "note_blocks", "ai_runs", "action_items", "briefs", "meeting_participants"}) {
                String column = table.equals("meetings") ? "id" : "meeting_id";
                assertThat(db.<Integer>read(c -> c.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = 'a'").query(Integer.class).single())).as(table).isZero();
            }
            assertThat(Files.exists(data.resolve("audio/a"))).isFalse();
            assertThat(Files.exists(data.resolve("spool/a"))).isFalse();
            assertThat(Files.exists(data.resolve("recovery/a"))).isFalse();
            assertThat(Files.exists(data.resolve("audio/b/part.pcm"))).isTrue();
            assertThat(new SearchStore(db).search("unique")).allMatch(hit -> hit.meetingId().equals("b")).hasSize(2);
        }
    }

    @Test void traversalAndSymlinksCannotDeleteAnotherMeetingAndFailedDeleteCannotRestore() throws Exception {
        try (var db = SqliteDatabase.open(data.resolve("db"))) {
            var capture = new CaptureStore(db);
            var lifecycle = new LifecycleStore(db, data);
            Files.createDirectories(data.resolve("audio/b"));
            Path keep = data.resolve("audio/b/keep");
            Files.writeString(keep, "keep");
            for (String id : new String[]{"../b", "a"}) {
                capture.createMeeting(id, 0);
                capture.setStatus(id, MeetingStatus.DONE, 1);
                lifecycle.trash(id, 2);
            }
            Files.createSymbolicLink(data.resolve("audio/a"), data.resolve("audio/b"));
            assertThatThrownBy(() -> lifecycle.hardDelete("../b")).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> lifecycle.hardDelete("a")).isInstanceOf(RuntimeException.class);
            assertThat(lifecycle.restore("a", 3)).isFalse();
            assertThat(capture.findMeeting("a")).isEmpty();
            assertThat(Files.readString(keep)).isEqualTo("keep");
            Files.delete(data.resolve("audio/a"));
            lifecycle.hardDelete("a");
            assertThat(lifecycle.trash()).extracting(LifecycleStore.Trashed::id).doesNotContain("a");
        }
    }
}
