package clre20.zeroSentinel.record;

import java.util.UUID;
import java.util.function.Function;

/**
 * 單一玩家的錄製狀態：環形緩衝區＋（可選）進行中的錄製。
 * 封包大多來自同一條 netty 執行緒；開始/停止錄製來自主執行緒，所以全部方法都同步在本物件上（幾乎不會競爭）。
 */
public final class PlayerRecorder {

    private final UUID uuid;
    private final String name;
    private final PacketRing ring;
    private RecordingSession session;

    public PlayerRecorder(UUID uuid, String name, int bufferBytes) {
        this.uuid = uuid;
        this.name = name;
        this.ring = new PacketRing(bufferBytes);
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    /**
     * 記錄一個封包。
     *
     * @param size      封包內容長度
     * @param truncated 超過單筆上限，只記錄 ID 與長度
     */
    public synchronized void record(long nanos, int direction, int packetId, int size, boolean truncated,
                                    PacketRing.ByteSource source) {
        ring.append(nanos, direction, packetId, size, truncated, source);
        RecordingSession s = session;
        if (s != null) {
            s.append(nanos, direction, truncated, packetId, size, source);
            if (s.isOverSize()) {
                s.finish(RecordingSession.EndReason.SIZE_LIMIT);
                session = null;
            }
        }
    }

    /**
     * 開始錄製：先寫入緩衝區中 minNanos 之後的內容，之後的封包也會持續寫入。
     *
     * @param factory 依「最早一筆紀錄的時間」建立錄製
     * @return 新的錄製；已經在錄製中時回傳 null
     */
    public synchronized RecordingSession start(long minNanos, Function<Long, RecordingSession> factory) {
        if (session != null && !session.isFinished()) {
            return null;
        }
        long[] earliest = {Long.MAX_VALUE};
        ring.visit(minNanos, (nanos, direction, truncated, packetId, size, payload) -> {
            if (nanos < earliest[0]) {
                earliest[0] = nanos;
            }
        });
        long base = earliest[0] == Long.MAX_VALUE ? minNanos : earliest[0];
        RecordingSession s = factory.apply(base);
        ring.visit(minNanos, (nanos, direction, truncated, packetId, size, payload) ->
                s.append(nanos, direction, truncated, packetId, size, payload));
        session = s;
        return s;
    }

    public synchronized RecordingSession current() {
        return session;
    }

    public synchronized boolean isRecording() {
        return session != null && !session.isFinished();
    }

    /** @return 已停止的錄製；沒有在錄製時回傳 null */
    public synchronized RecordingSession stop(RecordingSession.EndReason reason) {
        RecordingSession s = session;
        if (s == null) {
            return null;
        }
        s.finish(reason);
        session = null;
        return s;
    }

    /** 到期就結束 */
    public synchronized void checkExpiry(long nowNanos) {
        if (session != null && session.isExpired(nowNanos)) {
            session.finish(RecordingSession.EndReason.DURATION);
            session = null;
        }
    }

    public synchronized void clearBuffer() {
        ring.clear();
    }
}
