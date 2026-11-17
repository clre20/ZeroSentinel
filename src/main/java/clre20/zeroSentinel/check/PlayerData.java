package clre20.zeroSentinel.check;

import java.util.UUID;

/**
 * 單一玩家的檢查狀態。
 * <ul>
 *     <li>volatile 欄位由 netty 執行緒寫入（收到封包時），主執行緒讀取。</li>
 *     <li>其他欄位只在主執行緒使用。</li>
 * </ul>
 * 只存 UUID 與數值，不持有 Player 物件。
 */
public final class PlayerData {

    private final UUID uuid;
    private final String name;
    private final boolean bedrock;
    private final int protocol;
    private final long joinNanos;

    /** 最後一次收到移動 / tick 結束類封包的時間 */
    volatile long lastMoveNanos;
    /** 最後一次收到任何封包的時間 */
    volatile long lastAnyNanos;
    /** 這個時間之前不檢查（進服、切換世界、重生） */
    volatile long exemptUntilNanos;
    /** 客戶端已回報載入完成（1.21.4 起的 PLAYER_LOADED，或已送過移動封包） */
    volatile boolean loaded;

    // ---- 主執行緒 ----
    int clientReportVl;
    long clientReportLastFlagNanos = Long.MIN_VALUE / 2;
    long clientReportLastViolationMillis;

    public PlayerData(UUID uuid, String name, boolean bedrock, int protocol, long nowNanos, long graceNanos) {
        this.uuid = uuid;
        this.name = name;
        this.bedrock = bedrock;
        this.protocol = protocol;
        this.joinNanos = nowNanos;
        this.lastMoveNanos = nowNanos;
        this.lastAnyNanos = nowNanos;
        this.exemptUntilNanos = nowNanos + graceNanos;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public boolean bedrock() {
        return bedrock;
    }

    public int protocol() {
        return protocol;
    }

    public long joinNanos() {
        return joinNanos;
    }

    public boolean loaded() {
        return loaded;
    }

    public long lastMoveNanos() {
        return lastMoveNanos;
    }

    public long lastAnyNanos() {
        return lastAnyNanos;
    }

    public void exemptFor(long nowNanos, long graceNanos) {
        exemptUntilNanos = Math.max(exemptUntilNanos, nowNanos + graceNanos);
    }

    /** 封包更新（netty 執行緒） */
    void onPacket(long nowNanos, boolean movement, boolean loadedPacket) {
        lastAnyNanos = nowNanos;
        if (movement) {
            lastMoveNanos = nowNanos;
            loaded = true;
        }
        if (loadedPacket) {
            loaded = true;
        }
    }
}
