package clre20.zeroSentinel.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RecordingStorageTest {

    @Test
    void fileNamesAreSafe(@TempDir Path dir) {
        RecordingStorage storage = new RecordingStorage(dir, Logger.getLogger("t"));
        UUID id = UUID.randomUUID();
        for (String evil : new String[]{"../../etc/passwd", ".BedrockGuy", "a/b\\c", "名字", "x".repeat(100), ""}) {
            Path p = storage.newFile(evil, id, "../tag");
            assertTrue(p.startsWith(storage.root()));
            String name = p.getFileName().toString();
            assertFalse(name.contains("/") || name.contains("\\") || name.contains(".."), name);
            assertTrue(name.endsWith(RecordingStorage.EXTENSION));
        }
    }

    @Test
    void cleanupByAgeAndSize(@TempDir Path dir) throws Exception {
        RecordingStorage storage = new RecordingStorage(dir, Logger.getLogger("t"));
        Path day = Files.createDirectories(dir.resolve("2026-01-01"));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            Path f = day.resolve("f" + i + RecordingStorage.EXTENSION);
            Files.write(f, new byte[1000]);
            Files.setLastModifiedTime(f, FileTime.fromMillis(now - (10 - i) * 86_400_000L));
        }
        Files.write(day.resolve("keep.txt"), new byte[10]);
        // 保留 5 天：刪掉 6~10 天前的 5 個（第 5 天整的那個剛好超過一點也算過期）
        int deleted = storage.cleanup(5, 0);
        assertTrue(deleted >= 5);
        long remaining = storage.totalSize();
        assertTrue(remaining <= 5000);
        // 容量上限 2500：刪到 2250 以下
        storage.cleanup(0, 2500);
        assertTrue(storage.totalSize() <= 2250);
        assertTrue(Files.exists(day.resolve("keep.txt")));
    }

    @Test
    void recoverPartFiles(@TempDir Path dir) throws Exception {
        RecordingStorage storage = new RecordingStorage(dir, Logger.getLogger("t"));
        Path day = Files.createDirectories(dir.resolve("2026-02-02"));
        Files.write(day.resolve("abc" + RecordingStorage.EXTENSION + ".part"), new byte[3]);
        assertEquals(1, storage.recoverPartFiles());
        assertTrue(Files.exists(day.resolve("abc-recovered" + RecordingStorage.EXTENSION)));
    }
}
