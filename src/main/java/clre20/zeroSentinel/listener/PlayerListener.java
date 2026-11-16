package clre20.zeroSentinel.listener;

import clre20.zeroSentinel.ZeroSentinel;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 進服開始追蹤、離線釋放所有與該玩家有關的快取與錄製 */
public final class PlayerListener implements Listener {

    private final ZeroSentinel plugin;

    public PlayerListener(ZeroSentinel plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getRecordingManager().track(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        plugin.getRecordingManager().untrack(uuid);
        plugin.getEventManager().onQuit(uuid);
    }
}
