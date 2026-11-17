package app.interline.persistence;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * The single SQLite connection of the core. SQLite allows one writer, so every statement goes through this
 * object's lock: there is no SQLITE_BUSY to handle, and readers see a consistent WAL snapshot.
 */
public final class SqliteDatabase implements AutoCloseable {
    private static final int BUSY_TIMEOUT_MS = 5000;
    private final Connection connection;
    private final JdbcClient jdbc;

    private SqliteDatabase(Connection connection) {
        this.connection = connection;
        this.jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    /** Create the file if needed, apply Flyway migrations, then open the long-lived connection. */
    public static SqliteDatabase open(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            String url = "jdbc:sqlite:" + file.toAbsolutePath();
            Flyway.configure().dataSource(url, null, null)
                    .locations("classpath:db/migration", "classpath:db/migration-m1", "classpath:db/migration-m2").load().migrate();
            SQLiteConfig config = new SQLiteConfig();
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
            config.enforceForeignKeys(true);
            config.setBusyTimeout(BUSY_TIMEOUT_MS);
            return new SqliteDatabase(DriverManager.getConnection(url, config.toProperties()));
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("cannot open database " + file, e);
        }
    }

    public synchronized <T> T read(Function<JdbcClient, T> work) {
        return work.apply(jdbc);
    }

    /** Run {@code work} atomically: commit when it returns, roll back when it throws. */
    public synchronized <T> T write(Function<JdbcClient, T> work) {
        try {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(jdbc);
                connection.commit();
                return result;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("database transaction failed", e);
        }
    }

    @Override public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot close database", e);
        }
    }
}
