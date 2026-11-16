package clre20.zeroSentinel.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * ZeroSentinel 所有箱子 UI 的簽署 Holder。其他插件可用
 * {@code inventory.getHolder() instanceof ZeroSentinelHolder} 或 {@link #getPlugin()} 辨識來源。
 * 每一格對應的玩家或事件在開啟時就固定，避免資料在介面開著時變動造成點錯。
 */
public final class ZeroSentinelHolder implements InventoryHolder {

    public enum Type {
        MAIN,
        PLAYER,
        EVENT
    }

    private final Plugin plugin;
    private final Type type;
    private final int page;
    private final UUID target;
    private final String targetName;
    private final long eventId;
    private final UUID[] slotPlayers = new UUID[54];
    private final long[] slotEvents = new long[54];
    private Inventory inventory;
    private boolean busy;

    ZeroSentinelHolder(Plugin plugin, Type type, int page, UUID target, String targetName, long eventId) {
        this.plugin = plugin;
        this.type = type;
        this.page = page;
        this.target = target;
        this.targetName = targetName;
        this.eventId = eventId;
    }

    Inventory create(int size, Component title) {
        this.inventory = Bukkit.createInventory(this, size, title);
        return inventory;
    }

    /** 簽署：此介面所屬的插件 */
    public Plugin getPlugin() {
        return plugin;
    }

    public Type getType() {
        return type;
    }

    int getPage() {
        return page;
    }

    UUID getTarget() {
        return target;
    }

    String getTargetName() {
        return targetName;
    }

    long getEventId() {
        return eventId;
    }

    void setSlotPlayer(int slot, UUID uuid) {
        slotPlayers[slot] = uuid;
    }

    UUID getSlotPlayer(int slot) {
        return slot >= 0 && slot < slotPlayers.length ? slotPlayers[slot] : null;
    }

    void setSlotEvent(int slot, long id) {
        slotEvents[slot] = id;
    }

    long getSlotEvent(int slot) {
        return slot >= 0 && slot < slotEvents.length ? slotEvents[slot] : 0L;
    }

    boolean isBusy() {
        return busy;
    }

    void setBusy(boolean busy) {
        this.busy = busy;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
