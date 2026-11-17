package clre20.zeroSentinel.event;

import clre20.zeroSentinel.storage.Database;
import clre20.zeroSentinel.storage.LruCache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * 事件與錯誤碼管理。
 * <ul>
 *     <li>同一位玩家、同一個分類，在合併視窗（預設 60 秒）內連續的違規算同一個事件。</li>
 *     <li>每次發訊息給玩家都產生一組新的錯誤碼；同一事件可以有很多組。</li>
 *     <li>讀取先查記憶體快取（包含還沒寫進資料庫的資料），沒有才非同步查資料庫。</li>
 * </ul>
 * 所有公開方法都是執行緒安全的；回傳的 future 在資料庫執行緒完成，呼叫端自行切回主執行緒。
 */
public final class EventManager {

    /** 玩家頁顯示的最近事件數 */
    public static final int RECENT_LIMIT = 18;

    public record FlagResult(EventRecord event, boolean isNew) {
    }

    public record Lookup(EventRecord event, List<CodeRecord> codes) {
    }

    private record OpenKey(UUID uuid, String category) {
    }

    private final Database database;
    private final IntSupplier mergeWindowSeconds;
    private final LruCache<Long, EventRecord> events;
    private final LruCache<String, CodeRecord> codes;
    private final LruCache<Long, List<CodeRecord>> eventCodes;
    private final Map<OpenKey, Long> open = new ConcurrentHashMap<>();
    /** 線上玩家最近的事件 ID（新到舊），離線時清除 */
    private final Map<UUID, Deque<Long>> recent = new ConcurrentHashMap<>();
    /** 每位玩家最後一個事件（離線後仍保留，進服被拒時用來產生錯誤碼） */
    private final LruCache<UUID, Long> lastEvent = new LruCache<>(2000);

    public EventManager(Database database, int cacheSize, IntSupplier mergeWindowSeconds) {
        this.database = database;
        this.mergeWindowSeconds = mergeWindowSeconds;
        this.events = new LruCache<>(cacheSize);
        this.codes = new LruCache<>(cacheSize * 2);
        this.eventCodes = new LruCache<>(cacheSize);
    }

    public void setCacheSize(int cacheSize) {
        events.setMaxSize(cacheSize);
        codes.setMaxSize(cacheSize * 2);
        eventCodes.setMaxSize(cacheSize);
    }

    /**
     * 記錄一次違規：在合併視窗內就併入既有事件，否則建立新事件。
     */
    public synchronized FlagResult flag(UUID uuid, String playerName, String category) {
        long now = System.currentTimeMillis();
        OpenKey key = new OpenKey(uuid, category);
        Long openId = open.get(key);
        if (openId != null) {
            EventRecord existing = events.get(openId);
            if (existing != null && now - existing.lastSeen() <= mergeWindowSeconds.getAsInt() * 1000L) {
                EventRecord updated = existing.withSeen(now);
                events.put(updated.id(), updated);
                database.upsertEvent(updated);
                return new FlagResult(updated, false);
            }
        }
        long id = EventId.next();
        EventRecord created = new EventRecord(id, uuid, playerName, category, now, now, 1,
                Verdict.NONE, null, null, 0, null);
        events.put(id, created);
        eventCodes.put(id, List.of());
        open.put(key, id);
        lastEvent.put(uuid, id);
        Deque<Long> list = recent.computeIfAbsent(uuid, u -> new ArrayDeque<>());
        synchronized (list) {
            list.addFirst(id);
            while (list.size() > RECENT_LIMIT) {
                list.removeLast();
            }
        }
        database.upsertEvent(created);
        return new FlagResult(created, true);
    }

    /**
     * 為事件產生一組新的錯誤碼（每次發訊息給玩家都呼叫一次）。
     */
    public CodeRecord issueCode(EventRecord event, MessageType type) {
        String code;
        int attempts = 0;
        do {
            code = ErrorCode.generate();
            attempts++;
        } while (codes.containsKey(code) && attempts < 8);
        CodeRecord record = new CodeRecord(code, event.id(), event.uuid(), System.currentTimeMillis(), type);
        codes.put(code, record);
        synchronized (this) {
            List<CodeRecord> existing = eventCodes.get(event.id());
            if (existing != null) {
                List<CodeRecord> copy = new ArrayList<>(existing.size() + 1);
                copy.addAll(existing);
                copy.add(record);
                eventCodes.put(event.id(), Collections.unmodifiableList(copy));
            }
        }
        database.insertCode(record);
        return record;
    }

    /** 依錯誤碼（內部格式）查詢事件與它的所有錯誤碼 */
    public CompletableFuture<Optional<Lookup>> lookupCode(String code) {
        CodeRecord cached = codes.get(code);
        CompletableFuture<Optional<CodeRecord>> codeFuture = cached != null
                ? CompletableFuture.completedFuture(Optional.of(cached))
                : database.findCode(code);
        return codeFuture.thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            CodeRecord c = opt.get();
            codes.put(c.code(), c);
            return lookupEvent(c.eventId());
        });
    }

    /** 依事件 ID 查詢事件與它的所有錯誤碼 */
    public CompletableFuture<Optional<Lookup>> lookupEvent(long eventId) {
        return findEvent(eventId).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            EventRecord event = opt.get();
            List<CodeRecord> cachedCodes = eventCodes.get(eventId);
            if (cachedCodes != null) {
                return CompletableFuture.completedFuture(Optional.of(new Lookup(event, cachedCodes)));
            }
            return database.codesForEvent(eventId).thenApply(list -> {
                List<CodeRecord> merged = Collections.unmodifiableList(new ArrayList<>(list));
                eventCodes.put(eventId, merged);
                return Optional.of(new Lookup(event, merged));
            });
        });
    }

    public CompletableFuture<Optional<EventRecord>> findEvent(long eventId) {
        EventRecord cached = events.get(eventId);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return database.findEvent(eventId).thenApply(opt -> {
            opt.ifPresent(e -> {
                // 讀取期間若有其他執行緒更新了快取，以快取為準
                EventRecord now = events.get(e.id());
                if (now == null) {
                    events.put(e.id(), e);
                }
            });
            EventRecord now = opt.map(e -> events.get(e.id())).orElse(null);
            return now != null ? Optional.of(now) : opt;
        });
    }

    /**
     * 設定判定。
     *
     * @return 更新後的事件；找不到事件時為 empty
     */
    public CompletableFuture<Optional<EventRecord>> verdict(long eventId, Verdict verdict, String by, String note) {
        return findEvent(eventId).thenApply(opt -> {
            if (opt.isEmpty()) {
                return Optional.<EventRecord>empty();
            }
            EventRecord updated;
            synchronized (this) {
                EventRecord base = events.get(eventId);
                if (base == null) {
                    base = opt.get();
                }
                updated = base.withVerdict(verdict, by, note, System.currentTimeMillis());
                events.put(eventId, updated);
            }
            database.upsertEvent(updated);
            return Optional.of(updated);
        });
    }

    /** 錄製檔完成後寫回事件 */
    public void setRecording(long eventId, String path) {
        findEvent(eventId).thenAccept(opt -> opt.ifPresent(e -> {
            EventRecord updated;
            synchronized (this) {
                EventRecord base = events.get(eventId);
                updated = (base != null ? base : e).withRecording(path);
                events.put(eventId, updated);
            }
            database.upsertEvent(updated);
        }));
    }

    /** 某位玩家最近的事件（新到舊），合併資料庫與尚未寫入的資料 */
    public CompletableFuture<List<EventRecord>> recentEvents(UUID uuid) {
        return database.recentEvents(uuid, RECENT_LIMIT).thenApply(fromDb -> {
            Map<Long, EventRecord> merged = new LinkedHashMap<>();
            for (EventRecord e : fromDb) {
                EventRecord cached = events.get(e.id());
                merged.put(e.id(), cached != null ? cached : e);
            }
            Deque<Long> ids = recent.get(uuid);
            if (ids != null) {
                List<Long> copy;
                synchronized (ids) {
                    copy = new ArrayList<>(ids);
                }
                for (Long id : copy) {
                    EventRecord cached = events.get(id);
                    if (cached != null) {
                        merged.put(id, cached);
                    }
                }
            }
            List<EventRecord> out = new ArrayList<>(merged.values());
            out.sort(Comparator.comparingLong(EventRecord::created).reversed());
            return out.size() > RECENT_LIMIT ? new ArrayList<>(out.subList(0, RECENT_LIMIT)) : out;
        });
    }

    /** 玩家最後一個事件（快取 → 資料庫） */
    public CompletableFuture<Optional<EventRecord>> latestEvent(UUID uuid) {
        Long id = lastEvent.get(uuid);
        if (id != null) {
            EventRecord cached = events.get(id);
            if (cached != null) {
                return CompletableFuture.completedFuture(Optional.of(cached));
            }
            return findEvent(id);
        }
        return database.latestEvent(uuid);
    }

    /** 玩家離線：關閉他所有進行中的事件、清除最近事件清單 */
    public void onQuit(UUID uuid) {
        open.keySet().removeIf(k -> k.uuid().equals(uuid));
        recent.remove(uuid);
    }

    /** 移除超過合併視窗的進行中事件（定時呼叫，成本很低） */
    public void expireOpen() {
        long cutoff = System.currentTimeMillis() - mergeWindowSeconds.getAsInt() * 1000L;
        open.entrySet().removeIf(entry -> {
            EventRecord e = events.get(entry.getValue());
            return e == null || e.lastSeen() < cutoff;
        });
    }

    public void clear() {
        open.clear();
        recent.clear();
        lastEvent.clear();
        events.clear();
        codes.clear();
        eventCodes.clear();
    }
}
