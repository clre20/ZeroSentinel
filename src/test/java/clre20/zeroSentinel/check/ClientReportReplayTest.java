package clre20.zeroSentinel.check;

import clre20.zeroSentinel.record.RecordEntry;
import clre20.zeroSentinel.record.RecordingReader;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.PacketSide;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用真實錄製檔回放「客戶端回報異常 / 客戶端無回應」的判定規則，封包分類與正式程式完全相同。
 * 題庫不放進版本控制（含玩家資料），以 -Dzs.corpus=&lt;資料夾&gt; 指定，資料夾內需有 legit/、cheat/、frozen/。
 */
class ClientReportReplayTest {

    private static final long S = 1_000_000_000L;

    /** 回放結果 */
    record Result(int abnormal, long frozenAtSeconds, double durationSeconds) {
    }

    static Result replay(Path file, boolean checkBedrock) throws Exception {
        try (RecordingReader reader = new RecordingReader(file)) {
            boolean bedrock = reader.header().bedrock();
            long base = 1_000L * S;
            PlayerData d = new PlayerData(UUID.randomUUID(), "t", bedrock, reader.header().clientProtocol(), base, 0);
            d.loaded = true; // 錄製從遊戲中途開始
            int abnormal = 0;
            long frozenAt = -1;
            long nextTick = base + S;
            RecordEntry e;
            long last = base;
            while ((e = reader.next()) != null) {
                long t = base + e.timeMicros() * 1000L;
                while (nextTick <= t) {
                    ClientReportCheck.Kind k = ClientReportCheck.classify(d, nextTick, 10 * S, 10 * S, 60 * S, 60 * S, checkBedrock);
                    if (k == ClientReportCheck.Kind.ABNORMAL) {
                        d.clientReportLastFlagNanos = nextTick;
                        abnormal++;
                    } else if (k == ClientReportCheck.Kind.FROZEN) {
                        d.frozenKicked = true;
                        frozenAt = (nextTick - base) / S;
                    }
                    nextTick += S;
                }
                if (e.direction() == RecordEntry.CLIENT_TO_SERVER) {
                    PacketTypeCommon type = PacketType.getById(PacketSide.CLIENT, ConnectionState.PLAY, ClientVersion.V_26_3, e.packetId());
                    boolean movement = type instanceof PacketType.Play.Client c && CheckPacketListener.MOVEMENT.contains(c);
                    boolean active = type instanceof PacketType.Play.Client c2 && CheckPacketListener.ACTIVE.contains(c2);
                    d.onPacket(t, movement, type == PacketType.Play.Client.PLAYER_LOADED, active);
                }
                last = t;
            }
            return new Result(abnormal, frozenAt, (last - base) / (double) S);
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
            Result r = replay(f, false);
            assertEquals(0, r.abnormal(), "誤判（異常）：" + f.getFileName());
            assertEquals(-1, r.frozenAtSeconds(), "誤判（卡住）：" + f.getFileName());
            Result be = replay(f, true);
            assertEquals(0, be.abnormal() + (be.frozenAtSeconds() >= 0 ? 1 : 0), "基岩版也檢查時誤判：" + f.getFileName());
        }
        System.out.println("[legit] " + legit.size() + " 份全部 0 誤判");
    }

    @Test
    void cheatRecordingsAreCaught() throws Exception {
        for (Path f : files("cheat")) {
            Result r = replay(f, false);
            System.out.println("[cheat] " + f.getFileName() + " 異常=" + r.abnormal() + " 卡住踢出於=" + r.frozenAtSeconds()
                    + "s 長度=" + Math.round(r.durationSeconds()) + "s");
            if (r.durationSeconds() >= 70) {
                assertTrue(r.abnormal() > 0 || r.frozenAtSeconds() >= 0, "沒抓到：" + f.getFileName());
            }
        }
    }

    @Test
    void frozenClientIsNotTreatedAsCheat() throws Exception {
        for (Path f : files("frozen")) {
            Result r = replay(f, false);
            System.out.println("[frozen] " + f.getFileName() + " 異常=" + r.abnormal() + " 卡住踢出於=" + r.frozenAtSeconds() + "s");
            assertEquals(0, r.abnormal(), "卡住的客戶端被當成作弊：" + f.getFileName());
        }
    }

    @Test
    void classificationRules() {
        long base = 1_000L * S;
        // 只有 keep-alive：10 秒後不算異常，60 秒才以卡住處理
        PlayerData frozen = new PlayerData(UUID.randomUUID(), "f", false, 777, base, 0);
        frozen.loaded = true;
        frozen.onPacket(base + 30 * S, false, false, false);
        assertEquals(ClientReportCheck.Kind.NONE, ClientReportCheck.classify(frozen, base + 30 * S, 10 * S, 10 * S, 60 * S, 60 * S, false));
        frozen.onPacket(base + 61 * S, false, false, false);
        assertEquals(ClientReportCheck.Kind.FROZEN, ClientReportCheck.classify(frozen, base + 61 * S, 10 * S, 10 * S, 60 * S, 60 * S, false));
        frozen.frozenKicked = true;
        assertEquals(ClientReportCheck.Kind.NONE, ClientReportCheck.classify(frozen, base + 62 * S, 10 * S, 10 * S, 60 * S, 60 * S, false));
        // 卡住處理關閉
        PlayerData off = new PlayerData(UUID.randomUUID(), "o", false, 777, base, 0);
        off.loaded = true;
        off.onPacket(base + 90 * S, false, false, false);
        assertEquals(ClientReportCheck.Kind.NONE, ClientReportCheck.classify(off, base + 90 * S, 10 * S, 10 * S, 60 * S, 0, false));

        // 沒有移動卻下指令：10 秒就算異常
        PlayerData bot = new PlayerData(UUID.randomUUID(), "b", false, 774, base, 0);
        bot.loaded = true;
        bot.onPacket(base + 2 * S, false, false, true);
        bot.onPacket(base + 11 * S, false, false, false);
        assertEquals(ClientReportCheck.Kind.ABNORMAL, ClientReportCheck.classify(bot, base + 11 * S, 10 * S, 10 * S, 60 * S, 60 * S, false));
        bot.clientReportLastFlagNanos = base + 11 * S;
        bot.onPacket(base + 15 * S, false, false, false);
        assertEquals(ClientReportCheck.Kind.NONE, ClientReportCheck.classify(bot, base + 15 * S, 10 * S, 10 * S, 60 * S, 60 * S, false));

        // 進服後還沒送過移動就下指令（cynvo：進服 0.2 秒就 /help）：也算操作
        PlayerData fast = new PlayerData(UUID.randomUUID(), "fast", false, 774, base, 0);
        fast.loaded = true;
        fast.onPacket(base + S / 5, false, false, true);
        fast.onPacket(base + 11 * S, false, false, false);
        assertEquals(ClientReportCheck.Kind.ABNORMAL, ClientReportCheck.classify(fast, base + 11 * S, 10 * S, 10 * S, 60 * S, 60 * S, false));

        // 正常玩家：同一個 tick 內先點擊、再送 tick end → 不算「沒有移動卻在操作」
        PlayerData legit = new PlayerData(UUID.randomUUID(), "l", false, 777, base, 0);
        legit.loaded = true;
        legit.onPacket(base + S, false, false, true);
        legit.onPacket(base + S + 1, true, false, false);
        legit.onPacket(base + 12 * S, false, false, false);
        assertEquals(ClientReportCheck.Kind.NONE, ClientReportCheck.classify(legit, base + 12 * S, 10 * S, 10 * S, 60 * S, 60 * S, false));

        // 寬限與載入
        PlayerData join = new PlayerData(UUID.randomUUID(), "j", false, 777, base, 15 * S);
        join.onPacket(base + 5 * S, false, false, true);
        join.onPacket(base + 14 * S, false, false, false);
        assertEquals(ClientReportCheck.Kind.NONE, ClientReportCheck.classify(join, base + 14 * S, 10 * S, 10 * S, 60 * S, 60 * S, false));
        // 基岩版預設不檢查
        PlayerData be = new PlayerData(UUID.randomUUID(), ".b", true, 777, base, 0);
        be.loaded = true;
        be.onPacket(base + 2 * S, false, false, true);
        be.onPacket(base + 30 * S, false, false, false);
        assertEquals(ClientReportCheck.Kind.NONE, ClientReportCheck.classify(be, base + 30 * S, 10 * S, 10 * S, 60 * S, 60 * S, false));
        assertEquals(ClientReportCheck.Kind.ABNORMAL, ClientReportCheck.classify(be, base + 30 * S, 10 * S, 10 * S, 60 * S, 60 * S, true));
    }
}
