package clre20.zeroSentinel.check;

import clre20.zeroSentinel.ZeroSentinel;
import clre20.zeroSentinel.config.ConfigManager;
import clre20.zeroSentinel.punish.PunishmentManager;
import org.bukkit.entity.Player;

/**
 * 客戶端回報異常：正常的 Java 版客戶端（1.9～26.x），不論是否在掛機，每秒至少會送 1 個移動或 tick 結束封包。
 * 超過 silence-seconds 秒都沒有，但其他封包（keep-alive、聊天等）照常送達 → 違規。
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

    /**
     * 單一玩家此刻是否該判定一次違規（不含死亡、離線、伺服器停頓等需要伺服器狀態的條件）。
     * 不依賴 Bukkit，回放測試直接呼叫。
     */
    static boolean due(PlayerData d, long now, long silence, long interval, long maxLoad, boolean checkBedrock) {
        if (d.bedrock() && !checkBedrock) {
            return false;
        }
        if (now < d.exemptUntilNanos) {
            return false;
        }
        if (!d.loaded && now - d.joinNanos() < maxLoad) {
            return false;
        }
        if (now - d.lastMoveNanos < silence) {
            return false;
        }
        if (now - d.lastAnyNanos > CONNECTION_ALIVE_NANOS) {
            return false;
        }
        return now - d.clientReportLastFlagNanos >= interval;
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
        long nowMillis = System.currentTimeMillis();
        for (PlayerData d : players.all()) {
            if (!due(d, now, silence, interval, maxLoad, c.isClientReportCheckBedrock())) {
                continue;
            }
            Player p = plugin.getServer().getPlayer(d.uuid());
            if (p == null || p.isDead()) {
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
