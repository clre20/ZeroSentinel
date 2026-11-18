package clre20.zeroSentinel.punish;

import clre20.zeroSentinel.ZeroSentinel;
import clre20.zeroSentinel.command.Permissions;
import clre20.zeroSentinel.config.ConfigManager;
import clre20.zeroSentinel.config.MessageManager;
import clre20.zeroSentinel.event.CodeRecord;
import clre20.zeroSentinel.event.EventId;
import clre20.zeroSentinel.event.EventManager;
import clre20.zeroSentinel.event.MessageType;
import clre20.zeroSentinel.trust.TrustManager;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分級處罰：警告（扣信用）→ 通知管理員 → 踢出（扣更多信用）→ 信用過低時拒絕進服。
 * 只在主執行緒呼叫。
 */
public final class PunishmentManager {

    /** 一次違規的處理結果（測試與日誌使用） */
    public enum Outcome {
        WARNED,
        KICKED,
        KICKED_REPEAT,
        KICKED_TRUST,
        PAUSED_LOW_TPS
    }

    private final ZeroSentinel plugin;
    /** 各檢查最後一次踢出的時間（毫秒）；離線後仍保留，用來判斷「重新進服後再犯」 */
    private final Map<String, Long> lastKick = new ConcurrentHashMap<>();

    public PunishmentManager(ZeroSentinel plugin) {
        this.plugin = plugin;
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    private MessageManager msg() {
        return plugin.getMessageManager();
    }

    /**
     * 處理一次違規。
     *
     * @param check       檢查代號（用於事件分類與訊息 key），例如 {@code client-report}
     * @param category    事件分類名稱（顯示給管理員）
     * @param count       這次是第幾次違規（已累加）
     * @param baseKick    正常信用下累積幾次踢出
     * @param repeatMins  踢出後幾分鐘內再犯直接踢（0 = 關閉）
     * @param perViolation 每次違規扣的信用
     * @param perKick     踢出時再扣的信用
     */
    public Outcome violation(Player player, String check, String category, int count, int baseKick,
                             int repeatMins, int perViolation, int perKick) {
        UUID uuid = player.getUniqueId();
        EventManager events = plugin.getEventManager();
        TrustManager trust = plugin.getTrustManager();

        EventManager.FlagResult flag = events.flag(uuid, player.getName(), category);
        if (flag.isNew()) {
            plugin.getRecordingManager().startForEvent(uuid, flag.event().id());
        }
        trust.markViolation(uuid);
        String eventText = EventId.format(flag.event().id());

        double tps = plugin.getServer().getTPS()[0];
        if (tps < config().getLagMinTps()) {
            alert("alerts.paused", "player", player.getName(), "check", msg().raw("checks." + check + ".name"),
                    "tps", String.format("%.1f", tps), "event", eventText);
            return Outcome.PAUSED_LOW_TPS;
        }

        int before = trust.score(uuid);
        int score = trust.adjust(uuid, player.getName(), -perViolation);
        events.addTrustDeducted(flag.event().id(), before - score);
        int threshold = trust.threshold(baseKick, score);
        long now = System.currentTimeMillis();
        String kickKey = uuid + ":" + check;
        Long kickedAt = lastKick.get(kickKey);
        boolean repeat = repeatMins > 0 && kickedAt != null && now - kickedAt <= repeatMins * 60_000L;

        if (trust.isDenied(score)) {
            CodeRecord code = events.issueCode(flag.event(), MessageType.KICK);
            lastKick.put(kickKey, now);
            player.kick(msg().component("trust.login-denied", "trust", String.valueOf(score), "code", code.display()));
            alert("alerts.kicked-trust", "player", player.getName(), "trust", String.valueOf(score), "event", eventText);
            return Outcome.KICKED_TRUST;
        }

        if (repeat || count >= threshold) {
            int beforeKick = score;
            score = trust.adjust(uuid, player.getName(), -perKick);
            events.addTrustDeducted(flag.event().id(), beforeKick - score);
            CodeRecord code = events.issueCode(flag.event(), MessageType.KICK);
            lastKick.put(kickKey, now);
            if (trust.isDenied(score)) {
                player.kick(msg().component("trust.login-denied", "trust", String.valueOf(score), "code", code.display()));
                alert("alerts.kicked-trust", "player", player.getName(), "trust", String.valueOf(score), "event", eventText);
                return Outcome.KICKED_TRUST;
            }
            player.kick(msg().component("checks." + check + ".kick", "code", code.display()));
            alert(repeat ? "alerts.kicked-repeat" : "alerts.kicked", "player", player.getName(),
                    "check", msg().raw("checks." + check + ".name"), "trust", String.valueOf(score), "event", eventText);
            return repeat ? Outcome.KICKED_REPEAT : Outcome.KICKED;
        }

        CodeRecord code = events.issueCode(flag.event(), MessageType.WARN);
        msg().sendNoPrefix(player, "checks." + check + ".warn", "code", code.display(),
                "count", String.valueOf(count), "max", String.valueOf(threshold));
        alert("alerts.violation", "player", player.getName(), "check", msg().raw("checks." + check + ".name"),
                "count", String.valueOf(count), "max", String.valueOf(threshold), "trust", String.valueOf(score),
                "event", eventText);
        return Outcome.WARNED;
    }

    /**
     * 客戶端卡住（只剩網路層封包、沒有任何玩家操作）：不扣信用、不警告，直接以白話訊息踢出並附錯誤碼。
     * 不算入「踢出後再犯」。
     */
    public void frozenKick(Player player, long silentSeconds) {
        EventManager events = plugin.getEventManager();
        EventManager.FlagResult flag = events.flag(player.getUniqueId(), player.getName(), "ClientFrozen");
        if (flag.isNew()) {
            plugin.getRecordingManager().startForEvent(player.getUniqueId(), flag.event().id());
        }
        CodeRecord code = events.issueCode(flag.event(), MessageType.KICK);
        player.kick(msg().component("checks.client-frozen.kick", "code", code.display(),
                "seconds", String.valueOf(silentSeconds)));
        alert("alerts.frozen", "player", player.getName(), "seconds", String.valueOf(silentSeconds),
                "event", EventId.format(flag.event().id()));
    }

    /** 通知有 zerosentinel.alerts 權限的線上管理員，並寫入後台 */
    public void alert(String key, String... placeholders) {
        if (!config().isAlertsEnabled()) {
            return;
        }
        String text = msg().raw(key, placeholders);
        if (text.isEmpty()) {
            return;
        }
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasPermission(Permissions.ALERTS)) {
                msg().send(p, key, placeholders);
            }
        }
        plugin.getLogger().info(text.replaceAll("[&§][0-9a-fk-orA-FK-OR]", ""));
    }

    /** 移除過期的「最近踢出」紀錄（每分鐘呼叫） */
    public void prune(int maxRepeatMinutes) {
        long cutoff = System.currentTimeMillis() - Math.max(1, maxRepeatMinutes) * 60_000L;
        lastKick.values().removeIf(t -> t < cutoff);
    }

    public void clear() {
        lastKick.clear();
    }
}
