package clre20.zeroSentinel.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RecordingRoundTripTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static RecordingHeader header(UUID id, long eventId) {
        return new RecordingHeader(RecordingHeader.FORMAT_VERSION, "1.0.0", "26.3", 999, 998,
                id, "Steve", eventId, "test", System.currentTimeMillis(), true, new byte[0]);
    }

    @Test
    void writeThenReadBack(@TempDir Path dir) throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        UUID uuid = UUID.randomUUID();
        PlayerRecorder recorder = new PlayerRecorder(uuid, "Steve", 256 * 1024);
        long t0 = 1_000_000_000L;
        // 開始錄製前的封包（應包含在檔案內）
        for (int i = 0; i < 50; i++) {
            byte[] p = new byte[i];
            for (int j = 0; j < i; j++) {
                p[j] = (byte) (i + j);
            }
            recorder.record(t0 + i * 1_000_000L, i % 2, i, p.length, false,
                    (s, d, o, l) -> System.arraycopy(p, s, d, o, l));
        }
        CompletableFuture<RecordingSession.Result> done = new CompletableFuture<>();
        Path file = dir.resolve("2026-01-01").resolve("x" + RecordingStorage.EXTENSION);
        RecordingSession s = recorder.start(0, base -> new RecordingSession(io, LOG, file, header(uuid, 42L),
                base, Long.MAX_VALUE, 32L * 1024 * 1024, done::complete));
        assertNotNull(s);
        assertNull(recorder.start(0, base -> fail("should not start twice")));
        // 錄製中的封包，含一個超過 64KB 區塊的大封包
        byte[] big = new byte[200_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 7);
        }
        recorder.record(t0 + 100_000_000L, 1, 0x7E, big.length, false, (src, d, o, l) -> System.arraycopy(big, src, d, o, l));
        recorder.record(t0 + 101_000_000L, 0, 0x10, 5000, true, null);
        recorder.stop(RecordingSession.EndReason.STOPPED);

        RecordingSession.Result result = done.get(10, TimeUnit.SECONDS);
        assertTrue(result.success());
        assertEquals(52, result.entries());
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".part")));

        try (RecordingReader reader = new RecordingReader(file)) {
            RecordingHeader h = reader.header();
            assertEquals(uuid, h.uuid());
            assertEquals(42L, h.eventId());
            assertTrue(h.bedrock());
            assertEquals(998, h.clientProtocol());
            for (int i = 0; i < 50; i++) {
                RecordEntry e = reader.next();
                assertNotNull(e);
                assertEquals(i, e.packetId());
                assertEquals(i % 2, e.direction());
                assertEquals(i, e.payload().length);
                assertEquals(i * 1000L, e.timeMicros());
                for (int j = 0; j < i; j++) {
                    assertEquals((byte) (i + j), e.payload()[j]);
                }
            }
            RecordEntry bigEntry = reader.next();
            assertArrayEquals(big, bigEntry.payload());
            assertEquals(100_000L, bigEntry.timeMicros());
            RecordEntry trunc = reader.next();
            assertTrue(trunc.truncated());
            assertEquals(5000, trunc.size());
            assertNull(reader.next());
            assertFalse(reader.isIncomplete());
        }
        io.shutdown();
    }

    @Test
    void sizeLimitEndsSession(@TempDir Path dir) throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        UUID uuid = UUID.randomUUID();
        PlayerRecorder recorder = new PlayerRecorder(uuid, "Alex", 64 * 1024);
        CompletableFuture<RecordingSession.Result> done = new CompletableFuture<>();
        Path file = dir.resolve("a" + RecordingStorage.EXTENSION);
        recorder.start(0, base -> new RecordingSession(io, LOG, file, header(uuid, 0), base, Long.MAX_VALUE,
                10_000, done::complete));
        byte[] p = new byte[1000];
        for (int i = 0; i < 50 && recorder.isRecording(); i++) {
            recorder.record(i, 0, 1, p.length, false, (s, d, o, l) -> System.arraycopy(p, s, d, o, l));
        }
        assertFalse(recorder.isRecording());
        assertEquals(RecordingSession.EndReason.SIZE_LIMIT, done.get(10, TimeUnit.SECONDS).reason());
        io.shutdown();
    }

    @Test
    void truncatedFileIsReadUpToLastCompleteEntry(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken" + RecordingStorage.EXTENSION);
        try (OutputStream raw = Files.newOutputStream(file);
             java.io.DataOutputStream out = new java.io.DataOutputStream(new GZIPOutputStream(raw))) {
            header(UUID.randomUUID(), 0).write(out);
            byte[] buf = new byte[64];
            int pos = EntryEncoder.writeHeader(buf, 0, 0, false, 10, 3, 2);
            buf[pos++] = 1;
            buf[pos++] = 2;
            out.write(buf, 0, pos);
            // 寫一半的第二筆
            pos = EntryEncoder.writeHeader(buf, 0, 1, false, 10, 4, 50);
            out.write(buf, 0, pos);
            out.write(new byte[5]);
        }
        try (RecordingReader reader = new RecordingReader(file)) {
            assertNotNull(reader.next());
            assertNull(reader.next());
            assertTrue(reader.isIncomplete());
        }
    }

    @Test
    void rejectsNonRecording(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("fake" + RecordingStorage.EXTENSION);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write("hello world".getBytes());
        }
        assertThrows(java.io.IOException.class, () -> new RecordingReader(file));
    }
}
