package app.interline.persistence;

import java.nio.file.Path;
import java.util.List;

/** Durable deletion intent protects trashed meetings while their files are removed outside the capture lock. */
public final class LifecycleStore {
    private final SqliteDatabase database;
    private final java.util.function.Consumer<String> deleteFiles;
    public record Trashed(String id, String title, long deletedAt, boolean deletionStarted) { }

    public LifecycleStore(SqliteDatabase database, Path data) {
        this(database, id -> {
            new AudioStore(data.resolve("audio")).deleteMeeting(id);
            new AudioStore(data.resolve("spool")).deleteMeeting(id);
            new AudioStore(data.resolve("recovery")).deleteMeeting(id);
        });
    }

    LifecycleStore(SqliteDatabase database, java.util.function.Consumer<String> deleteFiles) {
        this.database = database;
        this.deleteFiles = deleteFiles;
    }

    public boolean containsMeeting(String id) {
        return database.read(db -> db.sql("SELECT count(*) FROM meetings WHERE id = ?").param(id).query(Integer.class).single() > 0);
    }

    public boolean trash(String id, long now) {
        return database.write(db -> db.sql("UPDATE meetings SET deleted_at = COALESCE(deleted_at, ?), updated_at = ? "
                + "WHERE id = ? AND status NOT IN ('recording','degraded','processing')")
                .params(now, now, id).update() > 0);
    }

    public List<Trashed> trash() {
        return database.read(db -> db.sql("SELECT id,title,deleted_at,deletion_started_at FROM meetings WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC,id")
                .query((rs,n) -> new Trashed(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getObject(4) != null)).list());
    }

    public boolean restore(String id, long now) {
        return database.write(db -> db.sql("UPDATE meetings SET deleted_at = NULL, updated_at = ? WHERE id = ? "
                + "AND deleted_at IS NOT NULL AND deletion_started_at IS NULL").params(now,id).update() > 0);
    }

    public synchronized boolean hardDelete(String id) { return delete(id, null); }

    private synchronized boolean delete(String id, Long trashedBefore) {
        // Commit intent first: neither restore nor a late agent reply may revive a partial deletion.
        boolean claimed = database.write(db -> db.sql("UPDATE meetings SET deletion_started_at = COALESCE(deletion_started_at, deleted_at) "
                + "WHERE id = ? AND deleted_at IS NOT NULL AND (? IS NULL OR deletion_started_at IS NOT NULL OR deleted_at < ?)")
                .params(id,trashedBefore,trashedBefore).update() > 0);
        if (!claimed) return false;
        deleteFiles.accept(id);
        database.write(db -> db.sql("DELETE FROM meetings WHERE id = ? AND deleted_at IS NOT NULL AND deletion_started_at IS NOT NULL")
                .param(id).update());
        return true;
    }

    public void purgeAudio(long now) {
        List<String> due = database.read(db -> db.sql("SELECT id FROM meetings WHERE audio_purge_at >= 0 AND audio_purge_at <= ? AND audio_dir IS NOT NULL "
                + "AND ended_at IS NOT NULL AND status NOT IN ('recording','degraded','processing') "
                + "AND NOT EXISTS (SELECT 1 FROM recovery_jobs WHERE meeting_id = meetings.id AND done = 0)")
                .param(now).query(String.class).list());
        RuntimeException failure = null;
        for (String id : due) {
            try { database.write(db -> {
                // Recheck under the write lock: the meeting might have resumed since the due list was read.
                boolean stillDue = db.sql("SELECT count(*) FROM meetings WHERE id = ? AND audio_purge_at >= 0 AND audio_purge_at <= ? AND ended_at IS NOT NULL "
                        + "AND status NOT IN ('recording','degraded','processing') "
                        + "AND NOT EXISTS (SELECT 1 FROM recovery_jobs WHERE meeting_id = meetings.id AND done = 0)").params(id,now).query(Integer.class).single() > 0;
                if (!stillDue) return null;
                deleteFiles.accept(id);
                return db.sql("UPDATE meetings SET audio_dir = NULL, audio_purge_at = NULL WHERE id = ?").param(id).update();
            }); } catch (RuntimeException e) { failure = e; }
        }
        if (failure != null) throw failure;
    }

    public void purgeTrash(long now) {
        RuntimeException failure = null;
        long cutoff = now - 30 * 86_400_000L;
        for (Trashed meeting : trash()) {
            if (meeting.deletionStarted() || meeting.deletedAt() < cutoff) {
                try { delete(meeting.id(),cutoff); }
                catch (RuntimeException e) { failure = e; }
            }
        }
        if (failure != null) throw failure;
    }
}
