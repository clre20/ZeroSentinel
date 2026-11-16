package clre20.zeroSentinel.record;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * 一次錄製（從環形緩衝區的內容開始，持續到指定時間）。
 * <ul>
 *     <li>紀錄先編碼進 64 KB 的記憶體區塊，區塊滿了才交給 I/O 執行緒寫入（gzip 串流）。</li>
 *     <li>錄製中寫入 {@code .part} 檔，結束後才改名為 {@code .zsr.gz}；當機留下的 .part 下次啟動會被修復。</li>
 *     <li>編碼的方法由 {@link PlayerRecorder} 在持有鎖的情況下呼叫；串流只在 I/O 執行緒操作。</li>
 * </ul>
 */
public final class RecordingSession {

    public enum EndReason {
        DURATION,
        STOPPED,
        SIZE_LIMIT,
        DISCONNECT,
        SHUTDOWN
    }

    static final int CHUNK_SIZE = 64 * 1024;

    private final Executor io;
    private final Logger logger;
    private final Path partFile;
    private final Path finalFile;
    private final RecordingHeader header;
    private final long baseNanos;
    private final long endNanos;
    private final long maxBytes;
    private final Consumer<Result> onFinished;

    private byte[] chunk = new byte[CHUNK_SIZE];
    private int chunkPos;
    private long lastNanos;
    private long totalBytes;
    private long entries;
    private boolean finished;

    /** 只在 I/O 執行緒使用 */
    private DataOutputStream stream;
    private boolean failed;

    public record Result(RecordingHeader header, Path file, long entries, long bytes, EndReason reason, boolean success) {
    }

    RecordingSession(Executor io, Logger logger, Path finalFile, RecordingHeader header,
                     long baseNanos, long endNanos, long maxBytes, Consumer<Result> onFinished) {
        this.io = io;
        this.logger = logger;
        this.finalFile = finalFile;
        this.partFile = finalFile.resolveSibling(finalFile.getFileName().toString() + ".part");
        this.header = header;
        this.baseNanos = baseNanos;
        this.lastNanos = baseNanos;
        this.endNanos = endNanos;
        this.maxBytes = maxBytes;
        this.onFinished = onFinished;
        io.execute(this::open);
    }

    public RecordingHeader header() {
        return header;
    }

    public Path file() {
        return finalFile;
    }

    public long endNanos() {
        return endNanos;
    }

    boolean isFinished() {
        return finished;
    }

    boolean isExpired(long nowNanos) {
        return nowNanos >= endNanos;
    }

    boolean isOverSize() {
        return totalBytes >= maxBytes;
    }

    /** 由環形緩衝區或已複製的陣列加入紀錄 */
    void append(long nanos, int direction, boolean truncated, int packetId, int size, byte[] payload) {
        appendInternal(nanos, direction, truncated, packetId, size, payload, null, truncated ? 0 : payload.length);
    }

    /** 由封包來源直接加入紀錄（不另外配置陣列） */
    void append(long nanos, int direction, boolean truncated, int packetId, int size, PacketRing.ByteSource source) {
        appendInternal(nanos, direction, truncated, packetId, size, null, source, truncated ? 0 : size);
    }

    private void appendInternal(long nanos, int direction, boolean truncated, int packetId, int size,
                                byte[] payload, PacketRing.ByteSource source, int payloadLen) {
        if (finished) {
            return;
        }
        if (CHUNK_SIZE - chunkPos < EntryEncoder.MAX_HEADER_BYTES) {
            flushChunk();
        }
        long delta = (Math.max(nanos, lastNanos) - lastNanos) / 1000L;
        lastNanos = Math.max(nanos, lastNanos);
        chunkPos = EntryEncoder.writeHeader(chunk, chunkPos, direction, truncated, delta, packetId, size);
        int written = 0;
        while (written < payloadLen) {
            if (chunkPos == CHUNK_SIZE) {
                flushChunk();
            }
            int n = Math.min(payloadLen - written, CHUNK_SIZE - chunkPos);
            if (payload != null) {
                System.arraycopy(payload, written, chunk, chunkPos, n);
            } else {
                source.copyTo(written, chunk, chunkPos, n);
            }
            chunkPos += n;
            written += n;
        }
        totalBytes += EntryEncoder.MAX_HEADER_BYTES + payloadLen;
        entries++;
    }

    private void flushChunk() {
        if (chunkPos == 0) {
            return;
        }
        final byte[] full = chunk;
        final int len = chunkPos;
        io.execute(() -> writeChunk(full, len));
        chunk = new byte[CHUNK_SIZE];
        chunkPos = 0;
    }

    /** 結束錄製；之後的紀錄都會被忽略 */
    void finish(EndReason reason) {
        if (finished) {
            return;
        }
        finished = true;
        flushChunk();
        chunk = null;
        final long count = entries;
        final long bytes = totalBytes;
        io.execute(() -> close(reason, count, bytes));
    }

    // ---- 以下只在 I/O 執行緒執行 ----

    private void open() {
        try {
            Files.createDirectories(partFile.getParent());
            OutputStream raw = Files.newOutputStream(partFile);
            stream = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw, 8192), 8192));
            header.write(stream);
        } catch (IOException e) {
            failed = true;
            logger.log(Level.WARNING, "無法建立錄製檔 " + partFile.getFileName(), e);
        }
    }

    private void writeChunk(byte[] data, int len) {
        if (failed || stream == null) {
            return;
        }
        try {
            stream.write(data, 0, len);
        } catch (IOException e) {
            failed = true;
            logger.log(Level.WARNING, "寫入錄製檔失敗 " + partFile.getFileName(), e);
        }
    }

    private void close(EndReason reason, long count, long bytes) {
        boolean ok = !failed && stream != null;
        if (stream != null) {
            try {
                if (ok) {
                    stream.write(RecordEntry.TYPE_END);
                }
                stream.close();
            } catch (IOException e) {
                ok = false;
                logger.log(Level.WARNING, "關閉錄製檔失敗 " + partFile.getFileName(), e);
            }
            stream = null;
        }
        if (ok) {
            try {
                Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                try {
                    Files.move(partFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    ok = false;
                    logger.log(Level.WARNING, "錄製檔改名失敗 " + partFile.getFileName(), ex);
                }
            }
        } else {
            try {
                Files.deleteIfExists(partFile);
            } catch (IOException ignored) {
                // 下次啟動的修復流程會處理
            }
        }
        if (onFinished != null) {
            try {
                onFinished.accept(new Result(header, finalFile, count, bytes, reason, ok));
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "處理錄製結束時發生錯誤", e);
            }
        }
    }

    long baseNanos() {
        return baseNanos;
    }
}
