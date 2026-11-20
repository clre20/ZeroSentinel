package clre20.zeroSentinel.check;

/**
 * 單一玩家的封包檢查狀態（只在該玩家的 netty 執行緒使用，不需同步）。
 * 不依賴 Bukkit 與 PacketEvents，可單獨測試。
 */
public final class PacketCheckState {

    private static final int TELEPORT_RING = 16;

    private int lastHeldSlot = -1;
    /** -1 = 未知、0 = 沒在疾跑、1 = 疾跑中 */
    private int sprinting = -1;
    private final int[] teleportIds = new int[TELEPORT_RING];
    private int teleportCount;
    private int maxTeleportId = Integer.MIN_VALUE;

    private final long[] lastReportNanos = new long[PacketRule.values().length];
    private final int[] pendingCounts = new int[PacketRule.values().length];

    public PacketCheckState() {
        java.util.Arrays.fill(lastReportNanos, Long.MIN_VALUE / 2);
    }

    /** 客戶端切換手持格 */
    PacketRule onHeldSlot(int slot) {
        if (PacketRule.invalidSlot(slot)) {
            return PacketRule.INVALID_SLOT;
        }
        boolean dup = slot == lastHeldSlot;
        lastHeldSlot = slot;
        return dup ? PacketRule.DUPLICATE_SLOT : null;
    }

    /** 伺服器設定手持格（之後客戶端可能再送同一格，不算重複） */
    void onServerHeldSlot() {
        lastHeldSlot = -1;
    }

    /** 客戶端開始 / 停止疾跑 */
    PacketRule onSprint(boolean start) {
        int now = start ? 1 : 0;
        boolean dup = sprinting == now;
        sprinting = now;
        return dup ? PacketRule.DUPLICATE_SPRINT : null;
    }

    /** 伺服器送出傳送 */
    void onTeleportSent(int id) {
        teleportIds[teleportCount++ % TELEPORT_RING] = id;
        if (id > maxTeleportId) {
            maxTeleportId = id;
        }
    }

    /**
     * 客戶端確認傳送：確認一個比伺服器送過的都還新的 ID 是不可能的（較舊、已不在紀錄中的 ID 不判定）。
     */
    PacketRule onTeleportConfirm(int id) {
        if (teleportCount == 0) {
            return null;
        }
        int n = Math.min(teleportCount, TELEPORT_RING);
        for (int i = 0; i < n; i++) {
            if (teleportIds[i] == id) {
                return null;
            }
        }
        return id > maxTeleportId ? PacketRule.INVALID_TELEPORT_CONFIRM : null;
    }

    /**
     * 回報節流：同一規則在 interval 內只回報一次，期間的次數累加到下一次回報。
     *
     * @return 這次要回報的累計次數；0 = 這次不回報
     */
    int report(PacketRule rule, long nowNanos, long intervalNanos) {
        int i = rule.ordinal();
        pendingCounts[i]++;
        if (nowNanos - lastReportNanos[i] < intervalNanos) {
            return 0;
        }
        lastReportNanos[i] = nowNanos;
        int count = pendingCounts[i];
        pendingCounts[i] = 0;
        return count;
    }
}
