package clre20.zeroSentinel.check;

/**
 * 封包層檢查規則。所有規則都已用正常玩家的錄製（1.20～26.3、基岩版）試跑，0 次觸發。
 * <ul>
 *     <li>{@code crash = true}：防崩服 / 漏洞類，基岩版也檢查；{@code cancel = true} 時會取消封包（設定可關）。</li>
 *     <li>{@code crash = false}：異常封包類（原版客戶端不可能送出），基岩版預設不檢查。</li>
 * </ul>
 * 1.2.0 起全部只通知管理員（experimental），不扣信用、不警告、不踢出。
 */
public enum PacketRule {
    INVALID_POSITION("invalid-position", true, false),
    INVALID_ROTATION("invalid-rotation", false, false),
    INVALID_SLOT("invalid-slot", true, true),
    DUPLICATE_SLOT("duplicate-slot", false, false),
    DUPLICATE_SPRINT("duplicate-sprint", false, false),
    INVALID_TELEPORT_CONFIRM("invalid-teleport-confirm", false, false),
    ILLEGAL_FLIGHT("illegal-flight", false, false),
    ILLEGAL_CREATIVE("illegal-creative", false, true),
    SELF_INTERACT("self-interact", false, true),
    OVERSIZED_NAME("oversized-name", true, true),
    OVERSIZED_TAB_COMPLETE("oversized-tab-complete", true, true),
    OVERSIZED_PAYLOAD("oversized-payload", true, true),
    OVERSIZED_BOOK("oversized-book", true, true),
    OVERSIZED_SIGN("oversized-sign", true, true);

    private final String key;
    private final boolean crash;
    private final boolean cancel;

    PacketRule(String key, boolean crash, boolean cancel) {
        this.key = key;
        this.crash = crash;
        this.cancel = cancel;
    }

    /** 設定檔與訊息檔使用的 key */
    public String key() {
        return key;
    }

    public boolean crash() {
        return crash;
    }

    /** 觸發時是否取消封包（還要看設定 checks.packets.cancel） */
    public boolean cancel() {
        return cancel;
    }

    /** 事件分類名稱 */
    public String category() {
        return "Packet-" + key;
    }

    // ---------------- 無狀態判斷 ----------------

    /** 世界座標上限（原版世界邊界最大 2.9999984E7） */
    static final double MAX_XZ = 3.0E7;
    static final double MAX_Y = 2.0E7;

    static boolean invalidPosition(double x, double y, double z) {
        return !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > MAX_XZ || Math.abs(z) > MAX_XZ || Math.abs(y) > MAX_Y;
    }

    /** 原版客戶端的 pitch 一定在 -90～90 之間 */
    static boolean invalidRotation(float yaw, float pitch) {
        return !Float.isFinite(yaw) || !Float.isFinite(pitch) || pitch > 90.0f || pitch < -90.0f;
    }

    static boolean invalidSlot(int slot) {
        return slot < 0 || slot > 8;
    }
}
