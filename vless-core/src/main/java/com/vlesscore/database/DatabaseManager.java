package com.vlesscore.database;

import com.vlesscore.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private final String jdbcUrl;
    private Connection connection;
    private TokenDao tokenDao;

    public DatabaseManager(Path baseDir, AppConfig config) {
        Path dbPath = baseDir.resolve(config.getDatabaseFile());
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    }

    public void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(jdbcUrl);

            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
            }

            runMigrations();
            tokenDao = new TokenDao(connection);
            log.info("SQLite подключена: {}", jdbcUrl);

        } catch (Exception e) {
            throw new RuntimeException("Не удалось инициализировать БД", e);
        }
    }

    private void runMigrations() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER NOT NULL
                )
            """);

            int currentVersion = 0;
            var rs = st.executeQuery(
                    "SELECT version FROM schema_version ORDER BY version DESC LIMIT 1");
            if (rs.next()) currentVersion = rs.getInt(1);
            rs.close();

            if (currentVersion < 1) {
                log.info("Миграция v1: создание таблиц...");
                migration_v1(st);
            }
        }
    }

    private void migration_v1(Statement st) throws SQLException {
        st.execute("""
            CREATE TABLE IF NOT EXISTS tokens (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                token       TEXT    NOT NULL UNIQUE,
                status      TEXT    NOT NULL DEFAULT 'ACTIVE',
                created_at  INTEGER NOT NULL,
                expires_at  INTEGER NOT NULL,
                bytes_up    INTEGER NOT NULL DEFAULT 0,
                bytes_down  INTEGER NOT NULL DEFAULT 0,
                connections INTEGER NOT NULL DEFAULT 0
            )
        """);

        st.execute("CREATE INDEX IF NOT EXISTS idx_tokens_token ON tokens(token)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_tokens_status ON tokens(status)");

        st.execute("""
            CREATE TABLE IF NOT EXISTS token_uuid_map (
                uuid       TEXT PRIMARY KEY,
                token_ref  TEXT NOT NULL,
                FOREIGN KEY (token_ref) REFERENCES tokens(token) ON DELETE CASCADE
            )
        """);

        st.execute("""
            CREATE TABLE IF NOT EXISTS user_groups (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                name            TEXT    NOT NULL UNIQUE,
                max_speed       INTEGER NOT NULL DEFAULT 0,
                max_connections INTEGER NOT NULL DEFAULT 0
            )
        """);

        st.execute("""
            CREATE TABLE IF NOT EXISTS settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """);

        st.execute("INSERT OR REPLACE INTO schema_version (version) VALUES (1)");
        log.info("Миграция v1 завершена.");
    }

    public TokenDao getTokenDao() { return tokenDao; }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.error("Ошибка при закрытии БД", e);
        }
    }
}