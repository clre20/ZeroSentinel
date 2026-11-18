package clre20.zeroSentinel.check;

import clre20.zeroSentinel.ZeroSentinel;
import clre20.zeroSentinel.config.ConfigManager;
import clre20.zeroSentinel.punish.PunishmentManager;
import org.bukkit.entity.Player;

/**
 * 客戶端回報異常 / 客戶端無回應：正常的 Java 版客戶端（1.9～26.x），不論是否在掛機，每秒至少會送 1 個移動或 tick 結束封包。
 * 超過 silence-seconds 秒都沒有、但連線仍在時：
 * <ul>
 *     <li>期間有玩家操作（聊天、指令、點擊、攻擊…）→ 客戶端刻意不送（機器人、外掛）→ 完整處罰流程。</li>
 *     <li>只有 keep-alive 等網路層封包 → 客戶端卡住 → 超過 client-frozen 秒數後踢出，不扣信用、不警告。</li>
 * </ul>
 * <p>
 * 豁免：進服 / 切換世界 / 重生後的寬限時間、尚未載入完成（最多等 max-load-wait-seconds 秒）、死亡、
 * 伺服器整體停頓後的寬限時間、整條連線都沒有封包（可能是斷線中）、基岩版（預設）。
 * </p>
 * 每秒由主執行緒呼叫一次，只讀取時間戳，成本為 O(線上人數)。
 */
public final class ClientReportCheck {

    public static final String KEY = "client-report";
    public static final String CATEGORY = "ClientReport";
    /** 連線仍在送封包的判斷：最後一個封包在這段時間內 */
    private static final long CONNECTION_ALIVE_NANOS = 3_000_000_000L;

    private final ZeroSentinel plugin;
    private final PlayerDataManager players;

    public ClientReportCheck(ZeroSentinel plugin, PlayerDataManager players) {
        this.plugin = plugin;
        this.players = players;
    }

    /** 判定結果 */
    public enum Kind {
        /** 沒事 */
        NONE,
        /** 不送移動，但有玩家操作（聊天、指令、點擊…）→ 刻意不送，走完整處罰流程 */
        ABNORMAL,
        /** 不送移動，也沒有任何玩家操作，只剩網路層封包 → 客戶端卡住，踢出但不扣分 */
        FROZEN
    }

    /** 操作封包至少要比最後一個移動封包晚這麼久，才算「沒有移動卻在操作」 */
    private static final long ACTIVE_AFTER_MOVE_NANOS = 1_000_000_000L;

    /**
     * 單一玩家此刻的判定（不含死亡、離線、伺服器停頓等需要伺服器狀態的條件）。
     * 不依賴 Bukkit，回放測試直接呼叫。
     *
     * @param frozenKick 沒有任何操作時，連續多久沒回報就以「卡住」踢出（0 = 不處理卡住）
     */
    static Kind classify(PlayerData d, long now, long silence, long interval, long maxLoad,
                         long frozenKick, boolean checkBedrock) {
        if (d.bedrock() && !checkBedrock) {
            return Kind.NONE;
        }
        if (now < d.exemptUntilNanos) {
            return Kind.NONE;
        }
        if (!d.loaded && now - d.joinNanos() < maxLoad) {
            return Kind.NONE;
        }
        long silent = now - d.lastMoveNanos;
        if (silent < silence) {
            return Kind.NONE;
        }
        if (now - d.lastAnyNanos > CONNECTION_ALIVE_NANOS) {
            return Kind.NONE;
        }
        // 這次連線從沒送過移動封包時，進服後的任何操作都算；送過的話，操作要比最後一個移動封包晚 1 秒以上
        boolean active = d.movedOnce
                ? d.lastActiveNanos - d.lastMoveNanos > ACTIVE_AFTER_MOVE_NANOS
                : d.lastActiveNanos >= d.joinNanos();
        if (active) {
            return now - d.clientReportLastFlagNanos >= interval ? Kind.ABNORMAL : Kind.NONE;
        }
        if (frozenKick > 0 && silent >= frozenKick && !d.frozenKicked) {
            return Kind.FROZEN;
        }
        return Kind.NONE;
    }

    public void tick() {
        ConfigManager c = plugin.getConfigManager();
        if (!c.isClientReportEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (plugin.getLagMonitor().inGrace(now)) {
            return;
        }
        long silence = c.getClientReportSilenceSeconds() * 1_000_000_000L;
        long interval = c.getClientReportIntervalSeconds() * 1_000_000_000L;
        long maxLoad = c.getMaxLoadWaitSeconds() * 1_000_000_000L;
        long frozenKick = c.isClientFrozenEnabled() ? c.getClientFrozenKickSeconds() * 1_000_000_000L : 0L;
        long nowMillis = System.currentTimeMillis();
        for (PlayerData d : players.all()) {
            Kind kind = classify(d, now, silence, interval, maxLoad, frozenKick, c.isClientReportCheckBedrock());
            if (kind == Kind.NONE) {
                continue;
            }
            Player p = plugin.getServer().getPlayer(d.uuid());
            if (p == null || p.isDead()) {
                continue;
            }
            if (kind == Kind.FROZEN) {
                d.frozenKicked = true;
                plugin.getPunishmentManager().frozenKick(p, (now - d.lastMoveNanos) / 1_000_000_000L);
                continue;
            }
            d.clientReportLastFlagNanos = now;
            violate(p, d, nowMillis);
        }
    }

    /** 記一次違規並交給處罰流程（/zs debug violation 也使用） */
    public PunishmentManager.Outcome violate(Player p, PlayerData d, long nowMillis) {
        ConfigManager c = plugin.getConfigManager();
        if (nowMillis - d.clientReportLastViolationMillis > c.getClientReportRemoveAfterSeconds() * 1000L) {
            d.clientReportVl = 0;
        }
        d.clientReportVl++;
        d.clientReportLastViolationMillis = nowMillis;
        PunishmentManager.Outcome outcome = plugin.getPunishmentManager().violation(p, KEY, CATEGORY,
                d.clientReportVl, c.getClientReportKickAfter(), c.getClientReportRepeatKickMinutes(),
                c.getClientReportTrustPerViolation(), c.getClientReportTrustPerKick());
        if (outcome != PunishmentManager.Outcome.WARNED && outcome != PunishmentManager.Outcome.PAUSED_LOW_TPS) {
            d.clientReportVl = 0;
        }
        return outcome;
    }
}
