package clre20.zeroSentinel.replay;

import clre20.zeroSentinel.record.RecordEntry;
import clre20.zeroSentinel.record.RecordingReader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 回歸測試執行器。
 * <ul>
 *     <li>{@code legit/}：合法錄製檔，不可出現任何違規。</li>
 *     <li>{@code cheat/}：作弊錄製檔，必須全部被抓到。</li>
 * </ul>
 * 第 0 階段還沒有檢查項目，只驗證每個錄製檔都能完整讀取；第 1 階段起改為把封包餵給檢查引擎並比對結果。
 */
public final class RegressionRunner {

    private final PrintStream out;

    public RegressionRunner(PrintStream out) {
        this.out = out;
    }

    /** @return 0 = 全部通過；1 = 有失敗 */
    public int run(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("找不到資料夾：" + root);
        }
        int failed = 0;
        int total = 0;
        for (String group : List.of("legit", "cheat")) {
            Path dir = root.resolve(group);
            if (!Files.isDirectory(dir)) {
                out.println("[" + group + "] 資料夾不存在，略過");
                continue;
            }
            for (Path file : list(dir)) {
                total++;
                String result = check(file);
                if (result == null) {
                    out.println("[通過] " + group + "/" + file.getFileName());
                } else {
                    failed++;
                    out.println("[失敗] " + group + "/" + file.getFileName() + "：" + result);
                }
            }
        }
        out.println("共 " + total + " 個檔案，失敗 " + failed + " 個。");
        out.println("（第 0 階段：只驗證檔案可完整讀取，尚未執行檢查項目）");
        return failed == 0 ? 0 : 1;
    }

    private static List<Path> list(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir, 4)) {
            s.filter(ReplayCli::isRecording).sorted().forEach(files::add);
        }
        return files;
    }

    /** @return null = 通過；否則為失敗原因 */
    private static String check(Path file) {
        try (RecordingReader reader = new RecordingReader(file)) {
            long count = 0;
            RecordEntry e;
            while ((e = reader.next()) != null) {
                count++;
            }
            if (reader.isIncomplete()) {
                return "檔案不完整（題庫檔案必須是正常結束的錄製檔）";
            }
            if (count == 0) {
                return "沒有任何封包";
            }
            return null;
        } catch (IOException | RuntimeException ex) {
            return "無法讀取：" + ex.getMessage();
        }
    }
}
