package clre20.zeroSentinel.storage;

import clre20.zeroSentinel.event.CodeRecord;
import clre20.zeroSentinel.event.EventRecord;
import clre20.zeroSentinel.event.MessageType;
import clre20.zeroSentinel.event.Verdict;
import clre20.zeroSentinel.trust.TrustLogEntry;
import clre20.zeroSentinel.trust.TrustRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 資料庫（事件與錯誤碼）。
 * <ul>
 *     <li>寫入：只有一條寫入執行緒。資料先放進記憶體佇列，每 flush-seconds 秒或累積 batch-size 筆，
 *     用一個交易＋預先編譯的批次語句一次寫完；同一事件在同一批中多次更新只寫最後一次。</li>
 *     <li>讀取：一條專用讀取執行緒，使用自己的連線（WAL 模式下讀寫互不阻擋）。</li>
 *     <li>清理：分批刪除過期資料，每批 1000 筆，避免長時間鎖住資料庫。</li>
 * </ul>
 * 不依賴 Bukkit，回呼一律在資料庫執行緒完成，呼叫端自行切回主執行緒。
 */
public final class Database {

    private static final int SCHEMA_VERSION = 2;
    private static final int CLEANUP_BATCH = 1000;


    private final Path file;
    private final Logger logger;
    private final int batchSize;
    private final int flushSeconds;

    /**
     * 待寫入的事件（髒資料）：同一事件不論更新幾次只保留最新一份，記憶體用量只跟「不同事件數」有關，
     * 不會因為同一事件的大量違規而增加。
     */
    private final ConcurrentHashMap<Long, EventRecord> dirtyEvents = new ConcurrentHashMap<>();
    /** 待寫入的錯誤碼（每組都不同，只能逐筆寫） */
    private final ConcurrentLinkedQueue<CodeRecord> pendingCodes = new ConcurrentLinkedQueue<>();
    /** 待寫入的信用點數（髒資料：同一玩家只保留最新一份） */
    private final ConcurrentHashMap<UUID, TrustRecord> dirtyTrust = new ConcurrentHashMap<>();
    /** 待寫入的管理員修改紀錄 */
    private final ConcurrentLinkedQueue<TrustLogEntry> pendingTrustLogs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final ScheduledExecutorService writer;
    private final ExecutorService reader;

    /** 只在寫入執行緒使用 */
    private Connection writeConn;
    /** 只在讀取執行緒使用 */
    private Connection readConn;
    private volatile boolean closed;

    public Database(Path file, Logger logger, int flushSeconds, int batchSize) {
        this.file = file;
        this.logger = logger;
        this.flushSeconds = flushSeconds;
        this.batchSize = batchSize;
        this.writer = Executors.newSingleThreadScheduledExecutor(named("ZeroSentinel-DB-Writer"));
        this.reader = Executors.newSingleThreadExecutor(named("ZeroSentinel-DB-Reader"));
    }

    private static ThreadFactory named(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 建立連線與資料表（同步等待完成，只在啟動時呼叫一次）。
     */
    public void start() throws Exception {
        Files.createDirectories(file.getParent());
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        writer.submit(() -> {
            writeConn = DriverManager.getConnection(url);
            applyPragmas(writeConn, true);
            createSchema(writeConn);
            return null;
        }).get(30, TimeUnit.SECONDS);
        reader.submit(() -> {
            readConn = DriverManager.getConnection(url);
            applyPragmas(readConn, false);
            return null;
        }).get(30, TimeUnit.SECONDS);
        writer.scheduleWithFixedDelay(this::flushSafe, flushSeconds, flushSeconds, TimeUnit.SECONDS);
    }

    private static void applyPragmas(Connection c, boolean write) throws SQLException {
        try (Statement st = c.createStatement()) {
            if (write) {
                st.execute("PRAGMA journal_mode=WAL");
            }
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA temp_store=MEMORY");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA cache_size=-4000");
        }
    }

    private static void createSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS events ("
                    + "event_id INTEGER PRIMARY KEY,"
                    + "uuid TEXT NOT NULL,"
                    + "name TEXT,"
                    + "category TEXT NOT NULL,"
                    + "created INTEGER NOT NULL,"
                    + "last_seen INTEGER NOT NULL,"
                    + "violations INTEGER NOT NULL,"
                    + "verdict TEXT,"
                    + "verdict_by TEXT,"
                    + "verdict_note TEXT,"
                    + "verdict_at INTEGER,"
                    + "recording TEXT)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_uuid ON events(uuid, created DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_created ON events(created)");
            st.execute("CREATE TABLE IF NOT EXISTS codes ("
                    + "code TEXT PRIMARY KEY,"
                    + "event_id INTEGER NOT NULL,"
                    + "uuid TEXT NOT NULL,"
                    + "created INTEGER NOT NULL,"
                    + "type TEXT NOT NULL) WITHOUT ROWID");
            st.execute("CREATE INDEX IF NOT EXISTS idx_codes_event ON codes(event_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_codes_created ON codes(created)");
            st.execute("CREATE TABLE IF NOT EXISTS trust ("
                    + "uuid TEXT PRIMARY KEY,"
                    + "name TEXT,"
                    + "score INTEGER NOT NULL,"
                    + "daily_gain INTEGER NOT NULL,"
                    + "gain_day INTEGER NOT NULL,"
                    + "updated INTEGER NOT NULL) WITHOUT ROWID");
            st.execute("CREATE INDEX IF NOT EXISTS idx_trust_name ON trust(name COLLATE NOCASE)");
            st.execute("CREATE TABLE IF NOT EXISTS trust_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "uuid TEXT NOT NULL,"
                    + "actor TEXT NOT NULL,"
                    + "delta INTEGER NOT NULL,"
                    + "score INTEGER NOT NULL,"
                    + "reason TEXT,"
                    + "time INTEGER NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_trust_log_uuid ON trust_log(uuid, time DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_trust_log_time ON trust_log(time)");
            st.execute("PRAGMA user_version=" + SCHEMA_VERSION);
        }
    }

    // ---------------- 寫入 ----------------

    public void upsertEvent(EventRecord event) {
        if (closed) {
            return;
        }
        if (dirtyEvents.put(event.id(), event) == null) {
            queued.incrementAndGet();
        }
        maybeFlush();
    }

    public void insertCode(CodeRecord code) {
        if (closed) {
            return;
        }
        pendingCodes.add(code);
        queued.incrementAndGet();
        maybeFlush();
    }

    public void upsertTrust(TrustRecord record) {
        if (closed) {
            return;
        }
        if (dirtyTrust.put(record.uuid(), record) == null) {
            queued.incrementAndGet();
        }
        maybeFlush();
    }

    public void insertTrustLog(TrustLogEntry entry) {
        if (closed) {
            return;
        }
        pendingTrustLogs.add(entry);
        queued.incrementAndGet();
        maybeFlush();
    }

    private void maybeFlush() {
        if (queued.get() >= batchSize && flushScheduled.compareAndSet(false, true)) {
            try {
                writer.execute(() -> {
                    flushScheduled.set(false);
                    flushSafe();
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                flushScheduled.set(false);
            }
        }
    }

    private void flushSafe() {
        try {
            flush();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "寫入資料庫時發生錯誤", e);
        }
    }

    /** 只在寫入執行緒執行 */
    private void flush() {
        if (writeConn == null || (dirtyEvents.isEmpty() && pendingCodes.isEmpty()
                && dirtyTrust.isEmpty() && pendingTrustLogs.isEmpty())) {
            return;
        }
        Map<Long, EventRecord> events = new LinkedHashMap<>();
        for (Long id : dirtyEvents.keySet()) {
            EventRecord e = dirtyEvents.remove(id);
            if (e != null) {
                events.put(id, e);
            }
        }
        List<CodeRecord> codes = new ArrayList<>();
        CodeRecord c0;
        while ((c0 = pendingCodes.poll()) != null) {
            codes.add(c0);
        }
        List<TrustRecord> trusts = new ArrayList<>();
        for (UUID id : dirtyTrust.keySet()) {
            TrustRecord t = dirtyTrust.remove(id);
            if (t != null) {
                trusts.add(t);
            }
        }
        List<TrustLogEntry> logs = new ArrayList<>();
        TrustLogEntry l0;
        while ((l0 = pendingTrustLogs.poll()) != null) {
            logs.add(l0);
        }
        int drained = events.size() + codes.size() + trusts.size() + logs.size();
        queued.addAndGet(-drained);
        try {
            writeConn.setAutoCommit(false);
            if (!events.isEmpty()) {
                try (PreparedStatement ps = writeConn.prepareStatement(
                        "INSERT INTO events(event_id, uuid, name, category, created, last_seen, violations, "
                                + "verdict, verdict_by, verdict_note, verdict_at, recording) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?) "
                                + "ON CONFLICT(event_id) DO UPDATE SET last_seen=excluded.last_seen, "
                                + "violations=excluded.violations, verdict=excluded.verdict, "
                                + "verdict_by=excluded.verdict_by, verdict_note=excluded.verdict_note, "
                                + "verdict_at=excluded.verdict_at, recording=excluded.recording")) {
                    for (EventRecord e : events.values()) {
                        ps.setLong(1, e.id());
                        ps.setString(2, e.uuid().toString());
                        ps.setString(3, e.playerName());
                        ps.setString(4, e.category());
                        ps.setLong(5, e.created());
                        ps.setLong(6, e.lastSeen());
                        ps.setInt(7, e.violations());
                        ps.setString(8, e.verdict() == null || e.verdict() == Verdict.NONE ? null : e.verdict().name());
                        ps.setString(9, e.verdictBy());
                        ps.setString(10, e.verdictNote());
                        if (e.verdictAt() > 0) {
                            ps.setLong(11, e.verdictAt());
                        } else {
                            ps.setNull(11, Types.INTEGER);
                        }
                        ps.setString(12, e.recording());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (!codes.isEmpty()) {
                try (PreparedStatement ps = writeConn.prepareStatement(
                        "INSERT OR IGNORE INTO codes(code, event_id, uuid, created, type) VALUES(?,?,?,?,?)")) {
                    for (CodeRecord c : codes) {
                        ps.setString(1, c.code());
                        ps.setLong(2, c.eventId());
                        ps.setString(3, c.uuid().toString());
                        ps.setLong(4, c.created());
                        ps.setString(5, c.type().name());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (!trusts.isEmpty()) {
                try (PreparedStatement ps = writeConn.prepareStatement(
                        "INSERT INTO trust(uuid, name, score, daily_gain, gain_day, updated) VALUES(?,?,?,?,?,?) "
                                + "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, score=excluded.score, "
                                + "daily_gain=excluded.daily_gain, gain_day=excluded.gain_day, updated=excluded.updated")) {
                    for (TrustRecord t : trusts) {
                        ps.setString(1, t.uuid().toString());
                        ps.setString(2, t.name());
                        ps.setInt(3, t.score());
                        ps.setInt(4, t.dailyGain());
                        ps.setLong(5, t.gainDay());
                        ps.setLong(6, t.updated());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (!logs.isEmpty()) {
                try (PreparedStatement ps = writeConn.prepareStatement(
                        "INSERT INTO trust_log(uuid, actor, delta, score, reason, time) VALUES(?,?,?,?,?,?)")) {
                    for (TrustLogEntry t : logs) {
                        ps.setString(1, t.uuid().toString());
                        ps.setString(2, t.actor());
                        ps.setInt(3, t.delta());
                        ps.setInt(4, t.score());
                        ps.setString(5, t.reason());
                        ps.setLong(6, t.time());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            writeConn.commit();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "批次寫入資料庫失敗（" + drained + " 筆）", e);
            try {
                writeConn.rollback();
            } catch (SQLException ignored) {
                // 忽略
            }
        } finally {
            try {
                writeConn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 忽略
            }
        }
    }

    /**
     * 分批刪除早於 cutoff 的事件與錯誤碼（在寫入執行緒執行）。
     *
     * @param onDone 完成後回呼（資料庫執行緒），參數為刪除筆數
     */
    public void cleanup(long cutoffMillis, java.util.function.IntConsumer onDone) {
        if (closed) {
            return;
        }
        writer.execute(() -> {
            if (writeConn == null) {
                return;
            }
            flushSafe();
            int total = 0;
            try {
                total += deleteBatched("DELETE FROM codes WHERE code IN (SELECT code FROM codes WHERE created < ? LIMIT "
                        + CLEANUP_BATCH + ")", cutoffMillis);
                total += deleteBatched("DELETE FROM events WHERE event_id IN (SELECT event_id FROM events WHERE created < ? LIMIT "
                        + CLEANUP_BATCH + ")", cutoffMillis);
                total += deleteBatched("DELETE FROM trust_log WHERE id IN (SELECT id FROM trust_log WHERE time < ? LIMIT "
                        + CLEANUP_BATCH + ")", cutoffMillis);
            } catch (SQLException e) {
                logger.log(Level.WARNING, "清理過期資料失敗", e);
            }
            if (onDone != null) {
                onDone.accept(total);
            }
        });
    }

    private int deleteBatched(String sql, long cutoff) throws SQLException {
        int total = 0;
        try (PreparedStatement ps = writeConn.prepareStatement(sql)) {
            while (true) {
                ps.setLong(1, cutoff);
                int n = ps.executeUpdate();
                total += n;
                if (n < CLEANUP_BATCH) {
                    break;
                }
            }
        }
        return total;
    }

    // ---------------- 讀取 ----------------

    public CompletableFuture<Optional<EventRecord>> findEvent(long id) {
        return read(() -> {
            try (PreparedStatement ps = readConn.prepareStatement("SELECT * FROM events WHERE event_id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readEvent(rs)) : Optional.<EventRecord>empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<CodeRecord>> findCode(String code) {
        return read(() -> {
            try (PreparedStatement ps = readConn.prepareStatement("SELECT * FROM codes WHERE code=?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readCode(rs)) : Optional.<CodeRecord>empty();
                }
            }
        });
    }

    public CompletableFuture<List<CodeRecord>> codesForEvent(long eventId) {
        return read(() -> {
            List<CodeRecord> out = new ArrayList<>();
            try (PreparedStatement ps = readConn.prepareStatement(
                    "SELECT * FROM codes WHERE event_id=? ORDER BY created LIMIT 500")) {
                ps.setLong(1, eventId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readCode(rs));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<List<EventRecord>> recentEvents(UUID uuid, int limit) {
        return read(() -> {
            List<EventRecord> out = new ArrayList<>();
            try (PreparedStatement ps = readConn.prepareStatement(
                    "SELECT * FROM events WHERE uuid=? ORDER BY created DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, Math.max(1, Math.min(limit, 500)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readEvent(rs));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<Optional<TrustRecord>> findTrust(UUID uuid) {
        return read(() -> {
            try (PreparedStatement ps = readConn.prepareStatement("SELECT * FROM trust WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readTrust(rs)) : Optional.<TrustRecord>empty();
                }
            }
        });
    }

    /** 依名稱（不分大小寫）找最近更新的一筆 */
    public CompletableFuture<Optional<TrustRecord>> findTrustByName(String name) {
        return read(() -> {
            try (PreparedStatement ps = readConn.prepareStatement(
                    "SELECT * FROM trust WHERE name=? COLLATE NOCASE ORDER BY updated DESC LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readTrust(rs)) : Optional.<TrustRecord>empty();
                }
            }
        });
    }

    public CompletableFuture<Optional<EventRecord>> latestEvent(UUID uuid) {
        return read(() -> {
            try (PreparedStatement ps = readConn.prepareStatement(
                    "SELECT * FROM events WHERE uuid=? ORDER BY created DESC LIMIT 1")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(readEvent(rs)) : Optional.<EventRecord>empty();
                }
            }
        });
    }

    private static TrustRecord readTrust(ResultSet rs) throws SQLException {
        return new TrustRecord(UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getInt("score"),
                rs.getInt("daily_gain"), rs.getLong("gain_day"), rs.getLong("updated"));
    }

    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private <T> CompletableFuture<T> read(SqlSupplier<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("database closed"));
            return future;
        }
        try {
            reader.execute(() -> {
                try {
                    if (readConn == null) {
                        throw new SQLException("資料庫尚未開啟");
                    }
                    future.complete(query.get());
                } catch (SQLException | RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private static EventRecord readEvent(ResultSet rs) throws SQLException {
        long verdictAt = rs.getLong("verdict_at");
        if (rs.wasNull()) {
            verdictAt = 0;
        }
        return new EventRecord(
                rs.getLong("event_id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                rs.getString("category"),
                rs.getLong("created"),
                rs.getLong("last_seen"),
                rs.getInt("violations"),
                Verdict.fromDb(rs.getString("verdict")),
                rs.getString("verdict_by"),
                rs.getString("verdict_note"),
                verdictAt,
                rs.getString("recording"));
    }

    private static CodeRecord readCode(ResultSet rs) throws SQLException {
        return new CodeRecord(
                rs.getString("code"),
                rs.getLong("event_id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getLong("created"),
                MessageType.parse(rs.getString("type")));
    }

    // ---------------- 關閉 ----------------

    /** 寫完所有佇列中的資料後關閉（同步等待，最多 timeoutSeconds 秒） */
    public void close(int timeoutSeconds) {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.submit(() -> {
                flushSafe();
                closeQuietly(writeConn);
                writeConn = null;
            }).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(Level.WARNING, "關閉資料庫時未能寫完所有資料", e);
        }
        try {
            reader.submit(() -> {
                closeQuietly(readConn);
                readConn = null;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 忽略
        }
        writer.shutdownNow();
        reader.shutdownNow();
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // 忽略
            }
        }
    }

    /** 測試用：立即寫入並等待完成 */
    void flushNow() throws Exception {
        writer.submit(this::flushSafe).get(30, TimeUnit.SECONDS);
    }

    int pendingCount() {
        return queued.get();
    }

}
