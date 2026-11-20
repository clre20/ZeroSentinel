package clre20.zeroSentinel.listener;

import clre20.zeroSentinel.ZeroSentinel;
import clre20.zeroSentinel.check.PlayerData;
import clre20.zeroSentinel.compatibility.FloodgateHook;
import clre20.zeroSentinel.event.CodeRecord;
import clre20.zeroSentinel.event.EventManager;
import clre20.zeroSentinel.event.EventRecord;
import clre20.zeroSentinel.event.MessageType;
import clre20.zeroSentinel.trust.TrustRecord;
import clre20.zeroSentinel.util.Versions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 玩家生命週期：
 * <ul>
 *     <li>進服前（非主執行緒）：讀取信用點數，過低就拒絕進服。</li>
 *     <li>進服：建立檢查狀態、開始錄製追蹤、開始計算在線加分。</li>
 *     <li>切換世界、重生：給予寬限時間。</li>
 *     <li>離線：清除所有與該玩家有關的快取與錄製。</li>
 * </ul>
 */
public final class PlayerListener implements Listener {

    private static final long LOGIN_TIMEOUT_MS = 3000;

    /** 被拒絕進服的玩家短時間內重複連線時沿用同一組錯誤碼，避免不斷新增資料 */
    private static final long DENY_CODE_REUSE_MS = 10 * 60_000L;

    private final ZeroSentinel plugin;
    private final clre20.zeroSentinel.storage.LruCache<UUID, CodeRecord> deniedCodes =
            new clre20.zeroSentinel.storage.LruCache<>(500);

    public PlayerListener(ZeroSentinel plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        Optional<TrustRecord> trust = plugin.getTrustManager().loadBlocking(uuid, name, LOGIN_TIMEOUT_MS);
        if (trust.isEmpty() || !plugin.getTrustManager().isDenied(trust.get().score())) {
            return;
        }
        CodeRecord reuse = deniedCodes.get(uuid);
        if (reuse != null && System.currentTimeMillis() - reuse.created() < DENY_CODE_REUSE_MS) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.getMessageManager().component("trust.login-denied",
                            "trust", String.valueOf(trust.get().score()), "code", reuse.display()));
            return;
        }
        EventManager events = plugin.getEventManager();
        EventRecord last = null;
        try {
            last = events.latestEvent(uuid).get(LOGIN_TIMEOUT_MS, TimeUnit.MILLISECONDS).orElse(null);
        } catch (Exception e) {
            plugin.getLogger().warning("讀取 " + name + " 的最後事件失敗：" + e.getMessage());
        }
        if (last == null) {
            last = events.flag(uuid, name, "TrustDenied").event();
        }
        CodeRecord code = events.issueCode(last, MessageType.DENY_JOIN);
        deniedCodes.put(uuid, code);
        // 玩家沒有真的進服，不會觸發離線事件：在這裡清掉事件管理的線上狀態
        events.onQuit(uuid);
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                plugin.getMessageManager().component("trust.login-denied",
                        "trust", String.valueOf(trust.get().score()), "code", code.display()));
        plugin.getPunishmentManager().alert("alerts.join-denied", "player", name,
                "trust", String.valueOf(trust.get().score()), "code", code.display());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.getPlayerDataManager().put(plugin.newPlayerData(player));
        // 正常情況已在進服前載入；插件重載後加入的玩家在這裡補載入（非同步）
        plugin.getTrustManager().load(uuid, player.getName());
        plugin.getTrustManager().onJoin(uuid);
        plugin.getRecordingManager().track(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getPlayerDataManager().remove(uuid);
        plugin.getTrustManager().onQuit(uuid);
        plugin.getRecordingManager().untrack(uuid);
        plugin.getEventManager().onQuit(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        exempt(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        exempt(event.getPlayer());
    }

    private void exempt(Player player) {
        PlayerData d = plugin.getPlayerDataManager().get(player.getUniqueId());
        if (d != null) {
            d.exemptFor(System.nanoTime(), plugin.getConfigManager().getJoinGraceSeconds() * 1_000_000_000L);
        }
    }
}
