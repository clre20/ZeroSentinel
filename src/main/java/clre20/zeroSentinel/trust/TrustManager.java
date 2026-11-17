package clre20.zeroSentinel.trust;

import clre20.zeroSentinel.config.ConfigManager;
import clre20.zeroSentinel.storage.Database;
import clre20.zeroSentinel.storage.LruCache;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 信用點數。
 * <ul>
 *     <li>線上玩家的分數放在記憶體快取，所有讀取都不碰資料庫。</li>
 *     <li>修改時標記為髒資料，由資料庫寫入執行緒批次寫入（同一玩家只保留最新一份）。</li>
 *     <li>剛離線的玩家保留在小型 LRU 快取，重新進服時不會讀到尚未寫入的舊分數。</li>
 *     <li>管理員修改會留下紀錄（誰、何時、改多少、原因）。</li>
 * </ul>
 * 修改方法都同步在本物件上，主執行緒與資料庫回呼執行緒可以安全呼叫。
 */
public final class TrustManager {

    /** 系統自動扣分時的操作者名稱 */
    public static final String SYSTEM = "ZeroSentinel";

    public enum Operation {
        SET,
        ADD,
        TAKE
    }

    private final Database database;
    private final ConfigManager config;
    private final Logger logger;
    private final Map<UUID, TrustRecord> online = new ConcurrentHashMap<>();
    private final LruCache<UUID, TrustRecord> recentOffline = new LruCache<>(500);
    /** 線上玩家：進服時間、最後違規時間、最後加分時間（毫秒） */
    private final Map<UUID, long[]> gainState = new ConcurrentHashMap<>();

    public TrustManager(Database database, ConfigManager config, Logger logger) {
        this.database = database;
        this.config = config;
        this.logger = logger;
    }

    // ---------------- 讀取與載入 ----------------

    /**
     * 讀取分數（快取 → 資料庫 → 預設值）。在非主執行緒的進服前事件使用，最多等待 timeoutMs。
     *
     * @return 讀取失敗或逾時時回傳 empty（呼叫端應放行，避免資料庫問題擋住所有人）
     */
    public Optional<TrustRecord> loadBlocking(UUID uuid, String name, long timeoutMs) {
        try {
            // 進服前只放進「最近」快取：若之後進服失敗（例如白名單）不會殘留在線上快取
            return Optional.of(load(uuid, name, false).get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            logger.warning("讀取 " + name + " 的信用點數失敗，暫時放行：" + e.getMessage());
            return Optional.empty();
        }
    }

    /** 讀取分數並放進線上快取（進服後呼叫） */
    public CompletableFuture<TrustRecord> load(UUID uuid, String name) {
        return load(uuid, name, true);
    }

    private CompletableFuture<TrustRecord> load(UUID uuid, String name, boolean markOnline) {
        TrustRecord cached = cachedRecord(uuid);
        if (cached != null) {
            TrustRecord named = name != null && !name.equals(cached.name()) ? cached.withName(name) : cached;
            store(uuid, named, markOnline);
            return CompletableFuture.completedFuture(named);
        }
        return database.findTrust(uuid).thenApply(opt -> {
            synchronized (this) {
                TrustRecord now = cachedRecord(uuid);
                if (now != null) {
                    store(uuid, now, markOnline);
                    return now;
                }
                TrustRecord rec = opt.orElseGet(() -> newRecord(uuid, name));
                if (name != null && !name.equals(rec.name())) {
                    rec = rec.withName(name);
                }
                if (opt.isEmpty()) {
                    database.upsertTrust(rec);
                }
                store(uuid, rec, markOnline);
                return rec;
            }
        });
    }

    private void store(UUID uuid, TrustRecord rec, boolean markOnline) {
        if (markOnline) {
            online.put(uuid, rec);
            recentOffline.remove(uuid);
        } else {
            recentOffline.put(uuid, rec);
        }
    }

    private TrustRecord cachedRecord(UUID uuid) {
        TrustRecord r = online.get(uuid);
        return r != null ? r : recentOffline.get(uuid);
    }

    private TrustRecord newRecord(UUID uuid, String name) {
        return new TrustRecord(uuid, name, config.getTrustStart(), 0, today(), System.currentTimeMillis());
    }

    /** 線上玩家目前分數；尚未載入時回傳預設值 */
    public int score(UUID uuid) {
        TrustRecord r = cachedRecord(uuid);
        return r != null ? r.score() : config.getTrustStart();
    }

    public boolean isDenied(int score) {
        return score <= config.getTrustDenyJoinAt();
    }

    /**
     * 依分數調整處罰門檻：高信用需要更多次才處罰，低信用更快。
     */
    public int threshold(int base, int score) {
        double m = 1.0;
        if (score >= config.getTrustHighThreshold()) {
            m = config.getTrustHighMultiplier();
        } else if (score < config.getTrustLowThreshold()) {
            m = config.getTrustLowMultiplier();
        }
        return Math.max(1, (int) Math.round(base * m));
    }

    // ---------------- 修改 ----------------

    /** 系統自動加減分（線上玩家），回傳新分數 */
    public synchronized int adjust(UUID uuid, String name, int delta) {
        TrustRecord base = cachedRecord(uuid);
        if (base == null) {
            base = newRecord(uuid, name);
        }
        int score = clamp(base.score() + delta);
        TrustRecord updated = base.withScore(score, System.currentTimeMillis());
        if (online.containsKey(uuid)) {
            online.put(uuid, updated);
        } else {
            recentOffline.put(uuid, updated);
        }
        database.upsertTrust(updated);
        return score;
    }

    /**
     * 管理員修改（玩家可離線），會寫入修改紀錄。
     *
     * @return 修改後的紀錄
     */
    public CompletableFuture<TrustRecord> adminModify(UUID uuid, String name, Operation op, int value,
                                                      String actor, String reason) {
        TrustRecord cached = cachedRecord(uuid);
        CompletableFuture<TrustRecord> base = cached != null
                ? CompletableFuture.completedFuture(cached)
                : database.findTrust(uuid).thenApply(opt -> opt.orElseGet(() -> newRecord(uuid, name)));
        return base.thenApply(found -> {
            synchronized (this) {
                TrustRecord current = cachedRecord(uuid);
                if (current == null) {
                    current = found;
                }
                int before = current.score();
                int after = switch (op) {
                    case SET -> clamp(value);
                    case ADD -> clamp(before + value);
                    case TAKE -> clamp(before - value);
                };
                long now = System.currentTimeMillis();
                TrustRecord updated = current.withScore(after, now);
                if (online.containsKey(uuid)) {
                    online.put(uuid, updated);
                } else {
                    recentOffline.put(uuid, updated);
                }
                database.upsertTrust(updated);
                database.insertTrustLog(new TrustLogEntry(uuid, actor, after - before, after, reason, now));
                return updated;
            }
        });
    }

    /** 查詢（玩家可離線） */
    public CompletableFuture<Optional<TrustRecord>> find(UUID uuid) {
        TrustRecord cached = cachedRecord(uuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return database.findTrust(uuid);
    }

    /** 依名稱找離線玩家的紀錄 */
    public CompletableFuture<Optional<TrustRecord>> findByName(String name) {
        for (TrustRecord r : online.values()) {
            if (name.equalsIgnoreCase(r.name())) {
                return CompletableFuture.completedFuture(Optional.of(r));
            }
        }
        return database.findTrustByName(name);
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(config.getTrustMax(), score));
    }

    // ---------------- 在線加分 ----------------

    public void onJoin(UUID uuid) {
        long now = System.currentTimeMillis();
        gainState.put(uuid, new long[]{now, 0L, now});
    }

    public void markViolation(UUID uuid) {
        long[] s = gainState.get(uuid);
        if (s != null) {
            s[1] = System.currentTimeMillis();
        }
    }

    /**
     * 每分鐘由主執行緒呼叫：連續在線 every-minutes 分鐘都沒有違規就加分，每日有上限。
     */
    public synchronized void tickGain() {
        int amount = config.getTrustGainAmount();
        if (amount <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long need = config.getTrustGainMinutes() * 60_000L;
        long day = today();
        for (Map.Entry<UUID, long[]> e : gainState.entrySet()) {
            long[] s = e.getValue();
            long since = Math.max(s[0], Math.max(s[1], s[2]));
            if (now - since < need) {
                continue;
            }
            s[2] = now;
            TrustRecord r = online.get(e.getKey());
            if (r == null) {
                continue;
            }
            int used = r.gainDay() == day ? r.dailyGain() : 0;
            int add = Math.min(amount, config.getTrustDailyGainCap() - used);
            if (add <= 0 || r.score() >= config.getTrustMax()) {
                continue;
            }
            int score = clamp(r.score() + add);
            TrustRecord updated = r.withGain(score, used + (score - r.score()), day, now);
            online.put(e.getKey(), updated);
            database.upsertTrust(updated);
        }
    }

    /** 玩家離線：移到最近離線快取（資料已標記為髒資料，會由批次寫入存檔） */
    public void onQuit(UUID uuid) {
        gainState.remove(uuid);
        TrustRecord r = online.remove(uuid);
        if (r != null) {
            recentOffline.put(uuid, r);
        }
    }

    public void clear() {
        online.clear();
        gainState.clear();
        recentOffline.clear();
    }

    private static long today() {
        return LocalDate.now(ZoneId.systemDefault()).toEpochDay();
    }
}
