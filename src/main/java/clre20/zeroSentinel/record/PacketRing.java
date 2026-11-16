package clre20.zeroSentinel.record;

/**
 * 每位玩家一個的固定大小環形緩衝區，保存最近的封包。
 * <ul>
 *     <li>建立時一次配置陣列，之後寫入不配置任何物件。</li>
 *     <li>空間不足時從最舊的紀錄開始覆蓋。</li>
 *     <li>不是執行緒安全，由 {@link PlayerRecorder} 負責同步。</li>
 * </ul>
 * 每筆紀錄格式：int 總長度、long 奈秒時間、byte 旗標、int 封包 ID、int 原始長度、內容。
 */
public final class PacketRing {

    /** 來源資料的複製介面（實作端重用同一個物件，避免每個封包配置 lambda） */
    public interface ByteSource {
        void copyTo(int srcOffset, byte[] dst, int dstOffset, int length);
    }

    /** 讀出紀錄時的回呼 */
    public interface Visitor {
        void accept(long nanos, int direction, boolean truncated, int packetId, int size, byte[] payload);
    }

    static final int ENTRY_HEADER = 4 + 8 + 1 + 4 + 4;

    private final byte[] data;
    private final int capacity;
    private int head;
    private int tail;
    private int used;
    private int count;

    public PacketRing(int capacity) {
        if (capacity < ENTRY_HEADER * 4) {
            throw new IllegalArgumentException("capacity too small");
        }
        this.capacity = capacity;
        this.data = new byte[capacity];
    }

    public int capacity() {
        return capacity;
    }

    public int used() {
        return used;
    }

    public int count() {
        return count;
    }

    /**
     * 寫入一筆紀錄。內容超過緩衝區一半時改為只記錄 ID 與長度（截斷）。
     *
     * @param truncated 呼叫端已決定截斷（例如超過單筆大小上限）
     */
    public void append(long nanos, int direction, int packetId, int size, boolean truncated, ByteSource source) {
        boolean trunc = truncated || size < 0 || source == null || ENTRY_HEADER + (long) size > capacity / 2;
        int payload = trunc ? 0 : size;
        int total = ENTRY_HEADER + payload;
        while (capacity - used < total) {
            evictOldest();
        }
        int pos = tail;
        pos = putInt(pos, total);
        pos = putLong(pos, nanos);
        pos = putByte(pos, (direction & 1) | (trunc ? 2 : 0));
        pos = putInt(pos, packetId);
        pos = putInt(pos, Math.max(size, 0));
        if (payload > 0) {
            int first = Math.min(payload, capacity - pos);
            source.copyTo(0, data, pos, first);
            if (first < payload) {
                source.copyTo(first, data, 0, payload - first);
            }
            pos = (pos + payload) % capacity;
        }
        tail = pos;
        used += total;
        count++;
    }

    /** 依時間順序讀出時間 ≥ minNanos 的紀錄（讀出時會配置陣列，只在存檔時使用） */
    public void visit(long minNanos, Visitor visitor) {
        int pos = head;
        int remaining = count;
        while (remaining-- > 0) {
            int total = getInt(pos);
            long nanos = getLong(pos + 4);
            int flags = data[(pos + 12) % capacity] & 0xFF;
            int packetId = getInt(pos + 13);
            int size = getInt(pos + 17);
            if (nanos >= minNanos) {
                int payloadLen = total - ENTRY_HEADER;
                byte[] payload = new byte[payloadLen];
                int start = (pos + ENTRY_HEADER) % capacity;
                int first = Math.min(payloadLen, capacity - start);
                System.arraycopy(data, start, payload, 0, first);
                if (first < payloadLen) {
                    System.arraycopy(data, 0, payload, first, payloadLen - first);
                }
                visitor.accept(nanos, flags & 1, (flags & 2) != 0, packetId, size, payload);
            }
            pos = (pos + total) % capacity;
        }
    }

    public void clear() {
        head = 0;
        tail = 0;
        used = 0;
        count = 0;
    }

    private void evictOldest() {
        int total = getInt(head);
        head = (head + total) % capacity;
        used -= total;
        count--;
    }

    private int putByte(int pos, int value) {
        data[pos] = (byte) value;
        return (pos + 1) % capacity;
    }

    private int putInt(int pos, int value) {
        pos = putByte(pos, value >>> 24);
        pos = putByte(pos, value >>> 16);
        pos = putByte(pos, value >>> 8);
        return putByte(pos, value);
    }

    private int putLong(int pos, long value) {
        pos = putInt(pos, (int) (value >>> 32));
        return putInt(pos, (int) value);
    }

    private int getInt(int pos) {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            v = (v << 8) | (data[(pos + i) % capacity] & 0xFF);
        }
        return v;
    }

    private long getLong(int pos) {
        return ((long) getInt(pos) << 32) | (getInt(pos + 4) & 0xFFFFFFFFL);
    }
}
