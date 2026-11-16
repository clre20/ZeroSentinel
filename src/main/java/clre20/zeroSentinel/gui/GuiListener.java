package clre20.zeroSentinel.gui;

import clre20.zeroSentinel.ZeroSentinel;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * ZeroSentinel 箱子 UI 的點擊處理：
 * 只要上方是本插件的介面，所有點擊與拖曳一律取消（防止拿走或放入物品），只有左鍵點擊上方介面才會觸發功能。
 */
public final class GuiListener implements Listener {

    private final ZeroSentinel plugin;

    public GuiListener(ZeroSentinel plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ZeroSentinelHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClick() != ClickType.LEFT) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= GuiManager.SIZE) {
            return;
        }
        plugin.getGuiManager().handleClick(player, holder, slot);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ZeroSentinelHolder) {
            event.setCancelled(true);
        }
    }
}
