package clre20.zeroSentinel.check;

import clre20.zeroSentinel.record.RecordEntry;
import clre20.zeroSentinel.record.RecordingReader;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用真實錄製檔回放「客戶端回報異常」的判定規則。
 * 題庫不放進版本控制（含玩家資料），以 -Dzs.corpus=&lt;資料夾&gt; 指定，資料夾內需有 legit/ 與 cheat/。
 */
class ClientReportReplayTest {

    /** 26.3（協議 777）伺服器格式的移動 / tick 結束類封包 ID */
    private static final Set<Integer> MOVEMENT_777 = Set.of(0x0D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23);
    private static final int PLAYER_LOADED_777 = 0x2C;
    private static final long S = 1_000_000_000L;

    static int replay(Path file, boolean checkBedrock) throws Exception {
        try (RecordingReader reader = new RecordingReader(file)) {
            boolean bedrock = reader.header().bedrock();
            long base = 1_000L * S;
            PlayerData d = new PlayerData(UUID.randomUUID(), "t", bedrock, reader.header().clientProtocol(), base, 0);
            d.loaded = true; // 錄製從遊戲中途開始
            int flags = 0;
            long nextTick = base + S;
            RecordEntry e;
            long last = base;
            while ((e = reader.next()) != null) {
                long t = base + e.timeMicros() * 1000L;
                while (nextTick <= t) {
                    if (ClientReportCheck.due(d, nextTick, 10 * S, 10 * S, 60 * S, checkBedrock)) {
                        d.clientReportLastFlagNanos = nextTick;
                        flags++;
                    }
                    nextTick += S;
                }
                if (e.direction() == RecordEntry.CLIENT_TO_SERVER) {
                    d.onPacket(t, MOVEMENT_777.contains(e.packetId()), e.packetId() == PLAYER_LOADED_777);
                }
                last = t;
            }
            return flags;
        }
    }

    private static List<Path> files(String group) throws Exception {
        String root = System.getProperty("zs.corpus");
        Assumptions.assumeTrue(root != null && Files.isDirectory(Path.of(root, group)), "未指定題庫，略過");
        try (Stream<Path> s = Files.list(Path.of(root, group))) {
            return s.filter(p -> p.toString().endsWith(".zsr.gz")).sorted().toList();
        }
    }

    @Test
    void legitRecordingsNeverFlag() throws Exception {
        List<Path> legit = files("legit");
        assertFalse(legit.isEmpty());
        for (Path f : legit) {
            assertEquals(0, replay(f, false), "誤判：" + f.getFileName());
            // 基岩版也檢查一次，確認 Geyser 轉換後的封包不會誤判（目前預設不檢查基岩版）
            System.out.println("[legit] " + f.getFileName() + " 基岩版也檢查時的違規數 = " + replay(f, true));
        }
    }

    @Test
    void cheatRecordingsFlag() throws Exception {
        List<Path> cheat = files("cheat");
        assertFalse(cheat.isEmpty());
        for (Path f : cheat) {
            int flags = replay(f, false);
            System.out.println("[cheat] " + f.getFileName() + " 違規數 = " + flags);
            assertTrue(flags >= 3, "沒抓到：" + f.getFileName());
        }
    }

    @Test
    void graceAndLoadingAreRespected() {
        long base = 1_000L * S;
        PlayerData d = new PlayerData(UUID.randomUUID(), "t", false, 777, base, 15 * S);
        // 寬限時間內：不判定
        assertFalse(ClientReportCheck.due(d, base + 14 * S, 10 * S, 10 * S, 60 * S, false));
        // 寬限結束但尚未載入、未滿 60 秒：不判定
        d.onPacket(base + 20 * S, false, false);
        assertFalse(ClientReportCheck.due(d, base + 20 * S, 10 * S, 10 * S, 60 * S, false));
        // 超過 60 秒仍未載入、keep-alive 還在：判定
        d.onPacket(base + 61 * S, false, false);
        assertTrue(ClientReportCheck.due(d, base + 61 * S, 10 * S, 10 * S, 60 * S, false));
        // 整條連線超過 3 秒沒封包（可能斷線中）：不判定
        assertFalse(ClientReportCheck.due(d, base + 70 * S, 10 * S, 10 * S, 60 * S, false));
        // 有移動封包：不判定
        d.onPacket(base + 80 * S, true, false);
        assertFalse(ClientReportCheck.due(d, base + 85 * S, 10 * S, 10 * S, 60 * S, false));
        // 基岩版預設不檢查
        PlayerData be = new PlayerData(UUID.randomUUID(), ".b", true, 777, base, 0);
        be.onPacket(base + 30 * S, false, true);
        assertFalse(ClientReportCheck.due(be, base + 30 * S, 10 * S, 10 * S, 60 * S, false));
        assertTrue(ClientReportCheck.due(be, base + 30 * S, 10 * S, 10 * S, 60 * S, true));
    }
}
