package clre20.zeroSentinel.record;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 錄製檔的資料夾管理：命名、當機修復、保留天數與總容量上限。
 * 除了 {@link #newFile} 之外的方法都會碰硬碟，只能在 I/O 執行緒呼叫。
 */
public final class RecordingStorage {

    public static final String EXTENSION = ".zsr.gz";
    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9_]");
    private static final Pattern DATE_DIR = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    private final Path root;
    private final Logger logger;

    public RecordingStorage(Path root, Logger logger) {
        this.root = root.toAbsolutePath().normalize();
        this.logger = logger;
    }

    public Path root() {
        return root;
    }

    /**
     * 新錄製檔的路徑（只組路徑，不碰硬碟）。檔名只使用安全字元，避免玩家名稱造成路徑問題。
     */
    public Path newFile(String playerName, UUID uuid, String tag) {
        Instant now = Instant.now();
        LocalDate day = LocalDate.ofInstant(now, ZoneId.systemDefault());
        String time = TIME.format(now.atZone(ZoneId.systemDefault()));
        String safeName = SAFE_NAME.matcher(playerName == null ? "" : playerName).replaceAll("_");
        if (safeName.isEmpty() || safeName.length() > 32) {
            safeName = uuid.toString().substring(0, 8);
        }
        String safeTag = SAFE_NAME.matcher(tag == null ? "" : tag).replaceAll("_");
        if (safeTag.length() > 32) {
            safeTag = safeTag.substring(0, 32);
        }
        Path file = root.resolve(DAY.format(day)).resolve(time + "_" + safeName + "_" + safeTag + EXTENSION).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalStateException("錄製檔路徑不合法");
        }
        return file;
    }

    /** 當機留下的 .part 檔改名為 -recovered.zsr.gz（讀取工具可以讀到最後一筆完整紀錄） */
    public int recoverPartFiles() {
        int recovered = 0;
        for (Path file : listAll(".part")) {
            String name = file.getFileName().toString();
            String base = name.substring(0, name.length() - ".part".length());
            if (base.endsWith(EXTENSION)) {
                base = base.substring(0, base.length() - EXTENSION.length());
            }
            Path target = file.resolveSibling(base + "-recovered" + EXTENSION);
            try {
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                recovered++;
            } catch (IOException e) {
                logger.warning("無法修復錄製檔 " + name + "：" + e.getMessage());
            }
        }
        return recovered;
    }

    /**
     * 刪除超過保留天數的錄製檔；總容量超過上限時從最舊的開始刪，直到降到上限的 90%。
     *
     * @param retentionDays 0 代表不限制
     * @param maxTotalBytes 0 代表不限制
     * @return 刪除的檔案數
     */
    public int cleanup(int retentionDays, long maxTotalBytes) {
        List<FileInfo> files = new ArrayList<>();
        for (Path file : listAll(EXTENSION)) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                files.add(new FileInfo(file, attrs.lastModifiedTime().toMillis(), attrs.size()));
            } catch (IOException ignored) {
                // 檔案可能剛被刪除
            }
        }
        files.sort(Comparator.comparingLong(FileInfo::modified));
        int deleted = 0;
        long now = System.currentTimeMillis();
        long total = 0;
        for (FileInfo f : files) {
            total += f.size();
        }
        List<FileInfo> kept = new ArrayList<>();
        for (FileInfo f : files) {
            if (retentionDays > 0 && now - f.modified() > retentionDays * 86_400_000L) {
                if (delete(f.path())) {
                    deleted++;
                    total -= f.size();
                }
            } else {
                kept.add(f);
            }
        }
        if (maxTotalBytes > 0 && total > maxTotalBytes) {
            long target = maxTotalBytes * 9 / 10;
            for (FileInfo f : kept) {
                if (total <= target) {
                    break;
                }
                if (delete(f.path())) {
                    deleted++;
                    total -= f.size();
                }
            }
            logger.warning("錄製檔總容量超過上限，已刪除最舊的檔案。");
        }
        removeEmptyDayDirs();
        return deleted;
    }

    /** 目前錄製檔總大小（位元組） */
    public long totalSize() {
        long total = 0;
        for (Path file : listAll(EXTENSION)) {
            try {
                total += Files.size(file);
            } catch (IOException ignored) {
                // 忽略
            }
        }
        return total;
    }

    /** 某位玩家的錄製檔（依檔名中的玩家名稱比對），新到舊 */
    public List<Path> listFor(String playerName, int limit) {
        String safeName = SAFE_NAME.matcher(playerName == null ? "" : playerName).replaceAll("_");
        List<Path> out = new ArrayList<>();
        for (Path file : listAll(EXTENSION)) {
            String n = file.getFileName().toString();
            int first = n.indexOf('_');
            if (first > 0 && n.startsWith(safeName + "_", first + 1)) {
                out.add(file);
            }
        }
        out.sort(Comparator.comparing((Path p) -> p.getParent().getFileName().toString())
                .thenComparing(p -> p.getFileName().toString()).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private List<Path> listAll(String suffix) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (DirectoryStream<Path> days = Files.newDirectoryStream(root, p -> Files.isDirectory(p)
                && DATE_DIR.matcher(p.getFileName().toString()).matches())) {
            for (Path day : days) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(day,
                        p -> p.getFileName().toString().endsWith(suffix) && Files.isRegularFile(p))) {
                    for (Path f : files) {
                        out.add(f);
                    }
                }
            }
        } catch (IOException e) {
            logger.warning("無法讀取錄製資料夾：" + e.getMessage());
        }
        return out;
    }

    private void removeEmptyDayDirs() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> days = Files.newDirectoryStream(root, p -> Files.isDirectory(p)
                && DATE_DIR.matcher(p.getFileName().toString()).matches())) {
            for (Path day : days) {
                try (DirectoryStream<Path> inside = Files.newDirectoryStream(day)) {
                    if (!inside.iterator().hasNext()) {
                        Files.deleteIfExists(day);
                    }
                }
            }
        } catch (IOException ignored) {
            // 下次再清
        }
    }

    private boolean delete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.warning("無法刪除錄製檔 " + file.getFileName() + "：" + e.getMessage());
            return false;
        }
    }

    /** 相對於錄製資料夾的路徑，給資料庫與訊息顯示使用 */
    public String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private record FileInfo(Path path, long modified, long size) {
    }
}
