package clre20.zeroSentinel.record;

/**
 * 把封包紀錄編碼成錄製檔的位元組格式：
 * <pre>
 * byte    類型（方向 + 是否截斷）
 * varlong 與上一筆的時間差（微秒）
 * varint  封包 ID
 * varint  原始內容長度
 * bytes   內容（截斷時沒有）
 * </pre>
 * 編碼直接寫進呼叫端提供的陣列，不配置新物件。
 */
public final class EntryEncoder {

    /** 一筆紀錄的檔頭最多佔用的位元組：1 + 10 + 5 + 5 */
    public static final int MAX_HEADER_BYTES = 21;

    private EntryEncoder() {
    }

    /**
     * @return 寫入後的位置
     */
    public static int writeHeader(byte[] dst, int pos, int direction, boolean truncated,
                                  long deltaMicros, int packetId, int size) {
        dst[pos++] = (byte) RecordEntry.type(direction, truncated);
        pos = writeVarLong(dst, pos, Math.max(0, deltaMicros));
        pos = writeVarInt(dst, pos, packetId);
        pos = writeVarInt(dst, pos, size);
        return pos;
    }

    public static int writeVarInt(byte[] dst, int pos, int value) {
        while ((value & ~0x7F) != 0) {
            dst[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        dst[pos++] = (byte) value;
        return pos;
    }

    public static int writeVarLong(byte[] dst, int pos, long value) {
        while ((value & ~0x7FL) != 0) {
            dst[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        dst[pos++] = (byte) value;
        return pos;
    }
}
