package clre20.zeroSentinel.record;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * 讀取 .zsr.gz 錄製檔。不依賴 Bukkit，離線回放工具也使用這個類別。
 * 當機後留下的不完整檔案也能讀到最後一筆完整的紀錄。
 */
public final class RecordingReader implements Closeable {

    /** 單筆封包內容上限，避免損壞或惡意檔案造成大量配置記憶體 */
    private static final int MAX_PAYLOAD = 8 * 1024 * 1024;

    private final DataInputStream in;
    private final RecordingHeader header;
    private long timeMicros;
    private boolean ended;
    private boolean incomplete;

    public RecordingReader(Path file) throws IOException {
        this(Files.newInputStream(file));
    }

    public RecordingReader(InputStream raw) throws IOException {
        this.in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw, 8192), 8192));
        this.header = RecordingHeader.read(in);
    }

    public RecordingHeader header() {
        return header;
    }

    /** 檔案沒有正常結束（例如當機）；已讀到的紀錄仍然有效 */
    public boolean isIncomplete() {
        return incomplete;
    }

    /**
     * @return 下一筆紀錄；檔案結束時回傳 null
     */
    public RecordEntry next() throws IOException {
        if (ended) {
            return null;
        }
        try {
            int type = in.read();
            if (type < 0) {
                incomplete = true;
                ended = true;
                return null;
            }
            if (type == RecordEntry.TYPE_END) {
                ended = true;
                return null;
            }
            if (type > RecordEntry.TYPE_S2C_TRUNCATED) {
                throw new IOException("未知的紀錄類型：" + type);
            }
            long delta = readVarLong(in);
            int packetId = readVarInt(in);
            int size = readVarInt(in);
            if (size < 0 || size > MAX_PAYLOAD) {
                throw new IOException("封包長度不合法：" + size);
            }
            boolean truncated = type >= RecordEntry.TYPE_C2S_TRUNCATED;
            byte[] payload;
            if (truncated) {
                payload = new byte[0];
            } else {
                payload = new byte[size];
                in.readFully(payload);
            }
            timeMicros += delta;
            int direction = (type & 1) == 1 ? RecordEntry.SERVER_TO_CLIENT : RecordEntry.CLIENT_TO_SERVER;
            return new RecordEntry(timeMicros, direction, truncated, packetId, size, payload);
        } catch (EOFException e) {
            incomplete = true;
            ended = true;
            return null;
        }
    }

    static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("varint 過長");
            }
        }
    }

    static long readVarLong(DataInputStream in) throws IOException {
        long value = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 63) {
                throw new IOException("varlong 過長");
            }
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
