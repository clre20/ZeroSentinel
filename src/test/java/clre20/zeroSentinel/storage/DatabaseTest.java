package clre20.zeroSentinel.storage;

import clre20.zeroSentinel.event.CodeRecord;
import clre20.zeroSentinel.event.EventManager;
import clre20.zeroSentinel.event.EventRecord;
import clre20.zeroSentinel.event.MessageType;
import clre20.zeroSentinel.event.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    @Test
    void eventsAndCodesPersistAndMerge(@TempDir Path dir) throws Exception {
        Database db = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 500);
        db.start();
        EventManager events = new EventManager(db, 100, () -> 60);
        UUID player = UUID.randomUUID();

        EventManager.FlagResult first = events.flag(player, "Steve", "Debug");
        assertTrue(first.isNew());
        EventManager.FlagResult second = events.flag(player, "Steve", "Debug");
        assertFalse(second.isNew());
        assertEquals(first.event().id(), second.event().id());
        assertEquals(2, second.event().violations());
        EventManager.FlagResult other = events.flag(player, "Steve", "Reach");
        assertTrue(other.isNew());

        CodeRecord c1 = events.issueCode(second.event(), MessageType.WARN);
        CodeRecord c2 = events.issueCode(second.event(), MessageType.KICK);
        assertNotEquals(c1.code(), c2.code());

        // 尚未寫入資料庫也查得到（快取）
        EventManager.Lookup lookup = events.lookupCode(c2.code()).get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(first.event().id(), lookup.event().id());
        assertEquals(2, lookup.codes().size());

        db.flushNow();
        assertEquals(0, db.pendingCount());

        // 直接從資料庫讀取
        Optional<CodeRecord> fromDb = db.findCode(c1.code()).get(5, TimeUnit.SECONDS);
        assertTrue(fromDb.isPresent());
        assertEquals(MessageType.WARN, fromDb.get().type());
        List<CodeRecord> codes = db.codesForEvent(first.event().id()).get(5, TimeUnit.SECONDS);
        assertEquals(2, codes.size());
        EventRecord stored = db.findEvent(first.event().id()).get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(2, stored.violations());

        // 判定
        EventRecord judged = events.verdict(first.event().id(), Verdict.LEGIT, "Admin", "誤判")
                .get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Verdict.LEGIT, judged.verdict());
        db.flushNow();
        EventRecord judgedDb = db.findEvent(first.event().id()).get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Verdict.LEGIT, judgedDb.verdict());
        assertEquals("誤判", judgedDb.verdictNote());
        assertTrue(judgedDb.verdictAt() > 0);

        List<EventRecord> recent = events.recentEvents(player).get(5, TimeUnit.SECONDS);
        assertEquals(2, recent.size());
        assertEquals(other.event().id(), recent.get(0).id());

        // 新的 EventManager（模擬重啟後快取為空）也查得到
        EventManager fresh = new EventManager(db, 100, () -> 60);
        EventManager.Lookup again = fresh.lookupCode(c1.code()).get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(2, again.codes().size());
        assertEquals(Verdict.LEGIT, again.event().verdict());
        assertTrue(fresh.lookupCode("000000000").get(5, TimeUnit.SECONDS).isEmpty());

        // 清理：截止時間在未來 → 全部刪除
        int[] deleted = {-1};
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        db.cleanup(System.currentTimeMillis() + 60_000, n -> {
            deleted[0] = n;
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(4, deleted[0]);
        db.close(10);
    }

    @Test
    void batchSizeTriggersFlush(@TempDir Path dir) throws Exception {
        Database db = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 10);
        db.start();
        EventManager events = new EventManager(db, 1000, () -> 60);
        java.util.List<UUID> players = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            UUID u = UUID.randomUUID();
            players.add(u);
            events.flag(u, "P" + i, "Debug");
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (db.pendingCount() >= 10 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(db.pendingCount() < 10);
        db.close(10);
        // 關閉時剩下的也要寫完
        Database reopened = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 10);
        reopened.start();
        for (UUID u : players) {
            assertEquals(1, reopened.recentEvents(u, 10).get(5, TimeUnit.SECONDS).size());
        }
        reopened.close(5);
    }

    @Test
    void repeatedViolationsCoalesce(@TempDir Path dir) throws Exception {
        Database db = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 500);
        db.start();
        EventManager events = new EventManager(db, 100, () -> 60);
        UUID u = UUID.randomUUID();
        EventManager.FlagResult r = null;
        for (int i = 0; i < 10_000; i++) {
            r = events.flag(u, "Spam", "Debug");
        }
        // 同一事件 1 萬次違規，只佔 1 筆待寫入
        assertEquals(1, db.pendingCount());
        db.flushNow();
        assertEquals(10_000, db.findEvent(r.event().id()).get(5, TimeUnit.SECONDS).orElseThrow().violations());
        db.close(5);
    }
}
