package clre20.zeroSentinel.punish;

import clre20.zeroSentinel.config.ConfigManager;
import clre20.zeroSentinel.event.EventManager;
import clre20.zeroSentinel.event.Verdict;
import clre20.zeroSentinel.storage.Database;
import clre20.zeroSentinel.trust.TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VerdictServiceTest {

    @Test
    void legitVerdictRefundsExactDeduction(@TempDir Path dir) throws Exception {
        Database db = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 500);
        db.start();
        EventManager events = new EventManager(db, 100, () -> 60);
        TrustManager trust = new TrustManager(db, new ConfigManager(null), Logger.getLogger("t"));
        VerdictService service = new VerdictService(events, trust);
        UUID u = UUID.randomUUID();
        trust.load(u, "weather_girl").get(3, TimeUnit.SECONDS);

        // 模擬：警告 5 次（各 -5）＋踢出（-30）＝ -55，再加一個 -5 的另一事件
        long e1 = events.flag(u, "weather_girl", "ClientReport").event().id();
        for (int i = 0; i < 5; i++) {
            int before = trust.score(u);
            events.addTrustDeducted(e1, before - trust.adjust(u, "weather_girl", -5));
        }
        int beforeKick = trust.score(u);
        events.addTrustDeducted(e1, beforeKick - trust.adjust(u, "weather_girl", -30));
        long e2 = events.flag(u, "weather_girl", "Other").event().id();
        events.addTrustDeducted(e2, trust.score(u) - trust.adjust(u, "weather_girl", -5));
        assertEquals(440, trust.score(u));

        // 單一判定：退還 55
        VerdictService.Single one = service.judge(e1, Verdict.LEGIT, "Admin", null).get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(55, one.refund());
        assertEquals(495, trust.score(u));
        // 再判定一次不會重複退還
        assertEquals(0, service.judge(e1, Verdict.LEGIT, "Admin", null).get(5, TimeUnit.SECONDS).orElseThrow().refund());
        assertEquals(495, trust.score(u));

        // 批次：只剩 e2 未判定，判定作弊不退分
        VerdictService.Bulk cheat = service.judgeAll(u, "weather_girl", Verdict.CHEAT, "Admin", "批次").get(5, TimeUnit.SECONDS);
        assertEquals(1, cheat.count());
        assertEquals(0, cheat.refund());
        assertEquals(495, trust.score(u));
        // 全部都判定過了
        assertEquals(0, service.judgeAll(u, "weather_girl", Verdict.LEGIT, "Admin", null).get(5, TimeUnit.SECONDS).count());

        // 寫入後重開資料庫：扣分紀錄與判定都保留
        db.close(10);
        Database db2 = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 500);
        db2.start();
        var stored = db2.findEvent(e1).get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Verdict.LEGIT, stored.verdict());
        assertEquals(0, stored.trustDeducted());
        assertEquals(5, db2.findEvent(e2).get(5, TimeUnit.SECONDS).orElseThrow().trustDeducted());
        db2.close(5);
    }

    @Test
    void bulkLegitRefundsAllAtOnce(@TempDir Path dir) throws Exception {
        Database db = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 500);
        db.start();
        EventManager events = new EventManager(db, 100, () -> 60);
        TrustManager trust = new TrustManager(db, new ConfigManager(null), Logger.getLogger("t"));
        VerdictService service = new VerdictService(events, trust);
        UUID u = UUID.randomUUID();
        trust.load(u, "p").get(3, TimeUnit.SECONDS);
        for (String cat : new String[]{"A", "B", "C"}) {
            long id = events.flag(u, "p", cat).event().id();
            events.addTrustDeducted(id, trust.score(u) - trust.adjust(u, "p", -35));
        }
        assertEquals(395, trust.score(u));
        // 一部分已寫入資料庫、一部分仍在快取：批次判定兩邊都要找得到
        db.close(5);
        db = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 500);
        db.start();
        events = new EventManager(db, 100, () -> 60);
        trust = new TrustManager(db, new ConfigManager(null), Logger.getLogger("t"));
        service = new VerdictService(events, trust);
        trust.load(u, "p").get(3, TimeUnit.SECONDS);
        assertEquals(395, trust.score(u));
        VerdictService.Bulk r = service.judgeAll(u, "p", Verdict.LEGIT, "Admin", null).get(5, TimeUnit.SECONDS);
        assertEquals(3, r.count());
        assertEquals(105, r.refund());
        assertEquals(500, trust.score(u));
        db.close(5);
    }
}
