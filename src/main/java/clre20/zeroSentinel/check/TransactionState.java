package clre20.zeroSentinel.check;

/**
 * 單一玩家的 transaction（PING / PONG）狀態。不依賴 Bukkit 與 PacketEvents，可單獨測試。
 * <ul>
 *     <li>每送出一個 PING 就配一個遞增的序號；封包上的 ID 是負數（-1 ～ -30000 循環），不會和其他插件常用的正數衝突。</li>
 *     <li>同一條連線上封包依序抵達：收到序號 N 的 PONG，代表客戶端已處理 N 之前送出的所有封包。</li>
 *     <li>伺服器改變飛行 / 創造權限時，新狀態要等客戶端回應「之後送出的 PING」才確定；確定之前新舊狀態都算合法。</li>
 * </ul>
 * 所有方法同步在本物件上（送出與接收通常在同一條 netty 執行緒，定時送出在另一條執行緒，競爭極少）。
 */
public final class TransactionState {

    private static final int RING = 64;
    private static final int ID_SPAN = 30_000;

    private final int[] wireIds = new int[RING];
    private final long[] sentNanos = new long[RING];
    private long nextSeq;
    private long lastAckSeq = -1;
    private long lastPongNanos;
    private long lastSendNanos = Long.MIN_VALUE / 2;
    private double rttEmaMs = -1;
    private long lastRttMs = -1;

    // ---- 權限（飛行 / 創造）----
    private boolean confirmedAllowFly;
    private boolean confirmedCreative;
    private boolean hasPending;
    private boolean pendingAllowFly;
    private boolean pendingCreative;
    private long pendingSeq;

    public TransactionState() {
        java.util.Arrays.fill(wireIds, Integer.MIN_VALUE);
    }

    public static int wireId(long seq) {
        return -(int) (seq % ID_SPAN) - 1;
    }

    /** 是否為本插件使用的 ID 範圍 */
    public static boolean isOurs(int wireId) {
        return wireId < 0 && wireId >= -ID_SPAN;
    }

    /**
     * 準備送出一個 PING。
     *
     * @return 封包上的 ID
     */
    public synchronized int next(long nowNanos) {
        long seq = nextSeq++;
        int idx = (int) (seq % RING);
        int id = wireId(seq);
        wireIds[idx] = id;
        sentNanos[idx] = nowNanos;
        lastSendNanos = nowNanos;
        return id;
    }

    /**
     * 收到 PONG。
     *
     * @return 對應的序號；不是最近送出的 ID 時回傳 -1
     */
    public synchronized long onPong(int wireId, long nowNanos) {
        // 從最新往回找（PONG 依序抵達，通常是最舊的未確認那一個）
        for (long seq = nextSeq - 1; seq >= 0 && seq >= nextSeq - RING; seq--) {
            int idx = (int) (seq % RING);
            if (wireIds[idx] == wireId && seq > lastAckSeq) {
                lastAckSeq = seq;
                lastPongNanos = nowNanos;
                long rtt = Math.max(0, (nowNanos - sentNanos[idx]) / 1_000_000L);
                lastRttMs = rtt;
                rttEmaMs = rttEmaMs < 0 ? rtt : rttEmaMs * 0.8 + rtt * 0.2;
                if (hasPending && seq >= pendingSeq) {
                    confirmedAllowFly = pendingAllowFly;
                    confirmedCreative = pendingCreative;
                    hasPending = false;
                }
                return seq;
            }
        }
        return -1;
    }

    /** 進服時由伺服器狀態設定（已確定） */
    public synchronized void setConfirmed(boolean allowFly, boolean creative) {
        confirmedAllowFly = allowFly;
        confirmedCreative = creative;
        hasPending = false;
    }

    /**
     * 伺服器正要送出新的飛行 / 創造權限；要等客戶端回應下一個 PING 之後才確定。
     * 已有未確定的變更時，舊的「新狀態」視為已確定以外的另一個可能狀態會被覆蓋，因此先把它合併到已確定狀態中（取寬鬆的一方）。
     */
    public synchronized void serverAbilities(boolean allowFly, boolean creative) {
        if (hasPending) {
            // 連續變更：在客戶端確認前，三種狀態都有可能，取寬鬆的一方避免誤判
            confirmedAllowFly = confirmedAllowFly || pendingAllowFly;
            confirmedCreative = confirmedCreative || pendingCreative;
        }
        hasPending = true;
        pendingAllowFly = allowFly;
        pendingCreative = creative;
        pendingSeq = nextSeq;
    }

    /** 客戶端此刻是否可能被允許飛行（已確定或尚未確定的新狀態任一允許即可） */
    public synchronized boolean mayFly() {
        return confirmedAllowFly || (hasPending && pendingAllowFly);
    }

    /** 客戶端此刻是否可能在創造模式 */
    public synchronized boolean mayCreative() {
        return confirmedCreative || (hasPending && pendingCreative);
    }

    /** 平滑後的延遲（毫秒）；還沒有資料時回傳 -1 */
    public synchronized long pingMs() {
        return rttEmaMs < 0 ? -1 : Math.round(rttEmaMs);
    }

    public synchronized long lastRttMs() {
        return lastRttMs;
    }

    public synchronized long lastSendNanos() {
        return lastSendNanos;
    }

    public synchronized long lastPongNanos() {
        return lastPongNanos;
    }

    /** 已送出但尚未收到回應的 PING 數量 */
    public synchronized long pending() {
        return nextSeq - 1 - lastAckSeq;
    }

    public synchronized long sentCount() {
        return nextSeq;
    }
}
