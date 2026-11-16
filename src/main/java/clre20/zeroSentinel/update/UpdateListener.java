package clre20.zeroSentinel.update;

import clre20.zeroSentinel.ZeroSentinel;
import clre20.zeroSentinel.command.Permissions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

/**
 * 具備 zerosentinel.version 權限的管理員登入 3 秒後，若有新版本則提醒。
 */
public final class UpdateListener implements Listener {

    private static final long DELAY_TICKS = 60L;

    private final ZeroSentinel plugin;

    public UpdateListener(ZeroSentinel plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isUpdateNotifyOnJoin()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(Permissions.VERSION)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        plugin.getUpdateChecker().check(result -> {
            if (result.status() != UpdateChecker.Status.OUTDATED) {
                return;
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                Player online = plugin.getServer().getPlayer(uuid);
                if (online != null) {
                    plugin.getMessageManager().send(online, "update.join-notify",
                            "current", result.current(), "latest", result.latest(),
                            "url", UpdateChecker.RELEASES_URL);
                }
            }, DELAY_TICKS);
        });
    }
}
