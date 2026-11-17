package clre20.zeroSentinel.trust;

import clre20.zeroSentinel.config.ConfigManager;
import clre20.zeroSentinel.storage.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TrustManagerTest {

    /** ConfigManager 未載入時使用預設值（start 500、max 1000、deny-join-at 0、high 800×2、low 300×0.5） */
    private static ConfigManager defaults() {
        return new ConfigManager(null);
    }

    @Test
    void scoresThresholdsAndPersistence(@TempDir Path dir) throws Exception {
        Database db = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 500);
        db.start();
        TrustManager trust = new TrustManager(db, defaults(), Logger.getLogger("t"));
        UUID u = UUID.randomUUID();

        // 新玩家 500 分；進服前讀取不會留在線上快取
        assertEquals(500, trust.loadBlocking(u, "Steve", 3000).orElseThrow().score());
        trust.load(u, "Steve").get(3, TimeUnit.SECONDS);
        assertEquals(500, trust.score(u));

        // 門檻：正常 6 次、高信用 12 次、低信用 3 次
        assertEquals(6, trust.threshold(6, 500));
        assertEquals(12, trust.threshold(6, 800));
        assertEquals(3, trust.threshold(6, 299));
        assertEquals(1, trust.threshold(1, 0));

        // 扣分不會低於 0；≤ 0 拒絕進服
        assertEquals(470, trust.adjust(u, "Steve", -30));
        assertFalse(trust.isDenied(470));
        assertEquals(0, trust.adjust(u, "Steve", -10_000));
        assertTrue(trust.isDenied(0));

        // 離線後重新讀取：拿到的是最新分數（尚未寫入資料庫也一樣）
        trust.onQuit(u);
        assertEquals(0, trust.loadBlocking(u, "Steve", 3000).orElseThrow().score());

        // 管理員修改（離線）：set / add / take，並留下紀錄
        assertEquals(200, trust.adminModify(u, "Steve", TrustManager.Operation.SET, 200, "Admin", "客服單 #12")
                .get(3, TimeUnit.SECONDS).score());
        assertEquals(250, trust.adminModify(u, "Steve", TrustManager.Operation.ADD, 50, "Admin", null)
                .get(3, TimeUnit.SECONDS).score());
        assertEquals(1000, trust.adminModify(u, "Steve", TrustManager.Operation.ADD, 5000, "Admin", null)
                .get(3, TimeUnit.SECONDS).score());
        assertEquals(990, trust.adminModify(u, "Steve", TrustManager.Operation.TAKE, 10, "Admin", null)
                .get(3, TimeUnit.SECONDS).score());
        db.close(10);

        // 重開資料庫：分數已寫入；可依名稱找到
        Database db2 = new Database(dir.resolve("data.db"), Logger.getLogger("t"), 300, 500);
        db2.start();
        TrustManager fresh = new TrustManager(db2, defaults(), Logger.getLogger("t"));
        assertEquals(990, fresh.find(u).get(3, TimeUnit.SECONDS).orElseThrow().score());
        assertEquals(u, fresh.findByName("steve").get(3, TimeUnit.SECONDS).orElseThrow().uuid());
        assertTrue(fresh.findByName("nobody").get(3, TimeUnit.SECONDS).isEmpty());
        db2.close(5);
    }
}
