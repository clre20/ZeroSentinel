package clre20.zeroSentinel.replay;

import clre20.zeroSentinel.record.RecordEntry;
import clre20.zeroSentinel.record.RecordingHeader;
import clre20.zeroSentinel.record.RecordingReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * 離線回放工具（不需要開伺服器）：
 * <pre>
 * java -jar ZeroSentinel-x.y.z.jar info &lt;檔案&gt;
 * java -jar ZeroSentinel-x.y.z.jar dump &lt;檔案&gt; [最多筆數]
 * java -jar ZeroSentinel-x.y.z.jar test &lt;題庫資料夾&gt;
 * </pre>
 */
public final class ReplayCli {

    private ReplayCli() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length < 2) {
            usage(out);
            System.exit(1);
            return;
        }
        int code;
        try {
            code = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "info" -> info(Path.of(args[1]), out);
                case "dump" -> dump(Path.of(args[1]), args.length > 2 ? parseLimit(args[2]) : Long.MAX_VALUE, out);
                case "test" -> new RegressionRunner(out).run(Path.of(args[1]));
                default -> {
                    usage(out);
                    yield 1;
                }
            };
        } catch (IOException e) {
            out.println("錯誤：" + e.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    private static void usage(PrintStream out) {
        out.println("用法：");
        out.println("  info <檔案>             顯示錄製檔資訊");
        out.println("  dump <檔案> [最多筆數]  列出每一筆封包（時間、方向、封包 ID、大小）");
        out.println("  test <題庫資料夾>       執行回歸測試（資料夾內需有 legit/ 與 cheat/）");
    }

    private static long parseLimit(String s) {
        try {
            return Math.max(1, Long.parseLong(s));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    static int info(Path file, PrintStream out) throws IOException {
        try (RecordingReader reader = new RecordingReader(file)) {
            printHeader(reader.header(), out);
            long count = 0;
            long c2s = 0;
            long truncated = 0;
            long last = 0;
            RecordEntry e;
            while ((e = reader.next()) != null) {
                count++;
                if (e.direction() == RecordEntry.CLIENT_TO_SERVER) {
                    c2s++;
                }
                if (e.truncated()) {
                    truncated++;
                }
                last = e.timeMicros();
            }
            out.printf("封包總數：%d（玩家→伺服器 %d，伺服器→玩家 %d，截斷 %d）%n", count, c2s, count - c2s, truncated);
            out.printf("時間長度：%.3f 秒%n", last / 1_000_000.0);
            if (reader.isIncomplete()) {
                out.println("注意：檔案沒有正常結束（可能是當機），已讀到最後一筆完整紀錄。");
            }
        }
        return 0;
    }

    static int dump(Path file, long limit, PrintStream out) throws IOException {
        try (RecordingReader reader = new RecordingReader(file)) {
            printHeader(reader.header(), out);
            out.println("時間(秒)      方向  ID    大小");
            RecordEntry e;
            long n = 0;
            while (n < limit && (e = reader.next()) != null) {
                out.printf("%12.6f  %s  0x%02X  %d%s%n", e.timeMicros() / 1_000_000.0, e.directionName(),
                        e.packetId(), e.size(), e.truncated() ? "（截斷）" : "");
                n++;
            }
            if (reader.isIncomplete()) {
                out.println("注意：檔案沒有正常結束（可能是當機）。");
            }
        }
        return 0;
    }

    static void printHeader(RecordingHeader h, PrintStream out) {
        out.println("玩家：" + h.playerName() + "（" + h.uuid() + "）" + (h.bedrock() ? " [基岩版]" : ""));
        out.println("伺服器：" + h.serverVersion() + "（協議 " + h.serverProtocol() + "）  客戶端協議：" + h.clientProtocol());
        out.println("插件版本：" + h.pluginVersion() + "  原因：" + h.reason()
                + (h.eventId() != 0 ? "  事件：" + clre20.zeroSentinel.event.EventId.format(h.eventId()) : ""));
        out.println("開始時間：" + Instant.ofEpochMilli(h.startEpochMillis()));
        out.println("世界快照：" + (h.snapshot().length == 0 ? "無" : h.snapshot().length + " 位元組"));
    }

    static boolean isRecording(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().endsWith(".zsr.gz");
    }
}
