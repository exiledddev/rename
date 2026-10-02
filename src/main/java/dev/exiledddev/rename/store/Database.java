package dev.exiledddev.rename.store;

import dev.exiledddev.rename.nick.Nick;
import dev.exiledddev.rename.nick.NickStyle;
import dev.exiledddev.rename.nick.Skin;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Nickname storage. SQLite by default (a single rename.db file, nothing to set up), or MySQL when
 * several servers should share nicknames. Paper ships both JDBC drivers.
 *
 * <ul>
 *   <li>{@code players}: every player's real name and real skin, captured when they join</li>
 *   <li>{@code nicknames}: every nickname ever given; rows with no {@code ended_at} are active</li>
 *   <li>{@code settings}: small saved values such as the auto-nick session</li>
 * </ul>
 * All methods are synchronized, because they're called from the main thread and from async login.
 */
public final class Database {

    /** A past or current nickname. */
    public record HistoryEntry(UUID uuid, String realName, String nickname, NickStyle style, long nickedAt, @Nullable Long endedAt) {
    }

    /** A player's real identity. */
    public record PlayerRecord(UUID uuid, String realName, @Nullable Skin skin) {
    }

    private final Logger logger;
    private final Connection connection;
    private final boolean mysql;

    private Database(final Connection connection, final boolean mysql, final Logger logger) {
        this.connection = connection;
        this.mysql = mysql;
        this.logger = logger;
    }

    public static Database sqlite(final File file, final Logger logger) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (final ClassNotFoundException e) {
            throw new SQLException("The SQLite driver is missing from this server", e);
        }
        final Database database = new Database(DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath()), false, logger);
        database.createTables();
        return database;
    }

    public static Database mysql(final String host, final int port, final String name, final String user, final String password, final Logger logger) throws SQLException {
        final String url = "jdbc:mysql://" + host + ":" + port + "/" + name + "?useUnicode=true&characterEncoding=utf8&autoReconnect=true";
        final Database database = new Database(DriverManager.getConnection(url, user, password), true, logger);
        database.createTables();
        return database;
    }

    private void createTables() throws SQLException {
        final String id = this.mysql ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        try (Statement statement = this.connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS players ("
                + "uuid VARCHAR(36) PRIMARY KEY, real_name VARCHAR(16) NOT NULL, "
                + "skin_value TEXT, skin_signature TEXT, updated_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS nicknames ("
                + "id " + id + ", uuid VARCHAR(36) NOT NULL, real_name VARCHAR(16) NOT NULL, "
                + "nickname VARCHAR(16) NOT NULL, style VARCHAR(16) NOT NULL, "
                + "skin_value TEXT, skin_signature TEXT, nicked_by VARCHAR(36), "
                + "nicked_at BIGINT NOT NULL, ended_at BIGINT)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS settings (name VARCHAR(64) PRIMARY KEY, value TEXT)");
        }
        try (Statement statement = this.connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX idx_nicknames_uuid ON nicknames (uuid)");
        } catch (final SQLException alreadyExists) {
            // Index already there.
        }
    }

    public synchronized void close() {
        try {
            this.connection.close();
        } catch (final SQLException e) {
            this.logger.log(Level.WARNING, "Could not close the database", e);
        }
    }

    // ---- Players --------------------------------------------------------------------------

    public synchronized void savePlayer(final UUID uuid, final String realName, final @Nullable Skin skin) {
        try (PreparedStatement update = this.connection.prepareStatement(
            "UPDATE players SET real_name = ?, skin_value = ?, skin_signature = ?, updated_at = ? WHERE uuid = ?")) {
            update.setString(1, realName);
            setSkin(update, 2, skin);
            update.setLong(4, System.currentTimeMillis());
            update.setString(5, uuid.toString());
            if (update.executeUpdate() > 0) {
                return;
            }
        } catch (final SQLException e) {
            this.fail("save player " + realName, e);
            return;
        }
        try (PreparedStatement insert = this.connection.prepareStatement(
            "INSERT INTO players (uuid, real_name, skin_value, skin_signature, updated_at) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, realName);
            setSkin(insert, 3, skin);
            insert.setLong(5, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (final SQLException e) {
            this.fail("save player " + realName, e);
        }
    }

    public synchronized Map<UUID, PlayerRecord> loadPlayers() {
        final Map<UUID, PlayerRecord> players = new HashMap<>();
        try (PreparedStatement statement = this.connection.prepareStatement("SELECT uuid, real_name, skin_value, skin_signature FROM players");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                final UUID uuid = UUID.fromString(rows.getString(1));
                players.put(uuid, new PlayerRecord(uuid, rows.getString(2), skin(rows, 3)));
            }
        } catch (final SQLException e) {
            this.fail("load players", e);
        }
        return players;
    }

    // ---- Nicknames ------------------------------------------------------------------------

    public synchronized Map<UUID, Nick> loadActiveNicks() {
        final Map<UUID, Nick> nicks = new HashMap<>();
        try (PreparedStatement statement = this.connection.prepareStatement(
            "SELECT uuid, real_name, nickname, style, skin_value, skin_signature, nicked_at FROM nicknames WHERE ended_at IS NULL");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                final NickStyle style = NickStyle.parse(rows.getString(4));
                final UUID uuid = UUID.fromString(rows.getString(1));
                nicks.put(uuid, new Nick(uuid, rows.getString(2), rows.getString(3), style == null ? NickStyle.GENERIC : style, skin(rows, 5), rows.getLong(7)));
            }
        } catch (final SQLException e) {
            this.fail("load nicknames", e);
        }
        return nicks;
    }

    /** Ends the player's current nickname (if any) and records the new one. */
    public synchronized void startNick(final Nick nick, final @Nullable UUID nickedBy) {
        this.endNick(nick.uuid(), nick.nickedAt());
        try (PreparedStatement insert = this.connection.prepareStatement(
            "INSERT INTO nicknames (uuid, real_name, nickname, style, skin_value, skin_signature, nicked_by, nicked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, nick.uuid().toString());
            insert.setString(2, nick.realName());
            insert.setString(3, nick.nickname());
            insert.setString(4, nick.style().id());
            setSkin(insert, 5, nick.skin());
            insert.setString(7, nickedBy == null ? null : nickedBy.toString());
            insert.setLong(8, nick.nickedAt());
            insert.executeUpdate();
        } catch (final SQLException e) {
            this.fail("save nickname " + nick.nickname(), e);
        }
    }

    public synchronized void endNick(final UUID uuid, final long when) {
        try (PreparedStatement update = this.connection.prepareStatement("UPDATE nicknames SET ended_at = ? WHERE uuid = ? AND ended_at IS NULL")) {
            update.setLong(1, when);
            update.setString(2, uuid.toString());
            update.executeUpdate();
        } catch (final SQLException e) {
            this.fail("end nickname", e);
        }
    }

    /** The player's nicknames, newest first. */
    public synchronized List<HistoryEntry> history(final UUID uuid, final int limit) {
        return this.queryHistory("SELECT uuid, real_name, nickname, style, nicked_at, ended_at FROM nicknames WHERE uuid = ? ORDER BY nicked_at DESC LIMIT " + limit,
            uuid.toString());
    }

    /** Every time anyone had this nickname (ignoring case), newest first. */
    public synchronized List<HistoryEntry> findNickname(final String nickname, final int limit) {
        return this.queryHistory("SELECT uuid, real_name, nickname, style, nicked_at, ended_at FROM nicknames WHERE LOWER(nickname) = LOWER(?) ORDER BY nicked_at DESC LIMIT " + limit,
            nickname);
    }

    private List<HistoryEntry> queryHistory(final String sql, final String parameter) {
        final List<HistoryEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    final NickStyle style = NickStyle.parse(rows.getString(4));
                    final long ended = rows.getLong(6);
                    entries.add(new HistoryEntry(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getString(3),
                        style == null ? NickStyle.GENERIC : style, rows.getLong(5), rows.wasNull() ? null : ended));
                }
            }
        } catch (final SQLException e) {
            this.fail("read nickname history", e);
        }
        return entries;
    }

    // ---- Settings -------------------------------------------------------------------------

    public synchronized @Nullable String setting(final String name) {
        try (PreparedStatement statement = this.connection.prepareStatement("SELECT value FROM settings WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (final SQLException e) {
            this.fail("read setting " + name, e);
            return null;
        }
    }

    public synchronized void setting(final String name, final @Nullable String value) {
        try (PreparedStatement delete = this.connection.prepareStatement("DELETE FROM settings WHERE name = ?")) {
            delete.setString(1, name);
            delete.executeUpdate();
        } catch (final SQLException e) {
            this.fail("save setting " + name, e);
            return;
        }
        if (value == null) {
            return;
        }
        try (PreparedStatement insert = this.connection.prepareStatement("INSERT INTO settings (name, value) VALUES (?, ?)")) {
            insert.setString(1, name);
            insert.setString(2, value);
            insert.executeUpdate();
        } catch (final SQLException e) {
            this.fail("save setting " + name, e);
        }
    }

    // ---- Helpers --------------------------------------------------------------------------

    private static void setSkin(final PreparedStatement statement, final int index, final @Nullable Skin skin) throws SQLException {
        if (skin == null) {
            statement.setNull(index, Types.VARCHAR);
            statement.setNull(index + 1, Types.VARCHAR);
        } else {
            statement.setString(index, skin.value());
            statement.setString(index + 1, skin.signature());
        }
    }

    private static @Nullable Skin skin(final ResultSet rows, final int index) throws SQLException {
        final String value = rows.getString(index);
        return value == null ? null : new Skin(value, rows.getString(index + 1));
    }

    private void fail(final String what, final SQLException e) {
        this.logger.log(Level.SEVERE, "Database error while trying to " + what, e);
    }
}
