package clre20.zeroSentinel.record;

/**
 * 錄製檔中的一筆封包紀錄（回放時使用）。
 *
 * @param timeMicros 相對錄製開始的時間（微秒）
 * @param direction  {@link #CLIENT_TO_SERVER} 或 {@link #SERVER_TO_CLIENT}
 * @param truncated  封包超過大小上限，只記錄 ID 與原始長度，沒有內容
 * @param size       原始內容長度（位元組，不含封包 ID）
 * @param payload    封包內容（不含封包 ID）；truncated 時為空陣列
 */
public record RecordEntry(long timeMicros, int direction, boolean truncated, int packetId, int size, byte[] payload) {

    public static final int CLIENT_TO_SERVER = 0;
    public static final int SERVER_TO_CLIENT = 1;

    /** 檔案內的類型位元組 */
    static final int TYPE_C2S = 0;
    static final int TYPE_S2C = 1;
    static final int TYPE_C2S_TRUNCATED = 2;
    static final int TYPE_S2C_TRUNCATED = 3;
    static final int TYPE_END = 0xFF;

    static int type(int direction, boolean truncated) {
        return (direction == SERVER_TO_CLIENT ? 1 : 0) + (truncated ? 2 : 0);
    }

    public String directionName() {
        return direction == CLIENT_TO_SERVER ? "C2S" : "S2C";
    }
}
