package clre20.zeroSentinel.gui;

import clre20.zeroSentinel.ZeroSentinel;
import clre20.zeroSentinel.command.Permissions;
import clre20.zeroSentinel.command.ZeroSentinelCommand;
import clre20.zeroSentinel.config.MessageManager;
import clre20.zeroSentinel.event.CodeRecord;
import clre20.zeroSentinel.event.EventFormat;
import clre20.zeroSentinel.event.EventId;
import clre20.zeroSentinel.event.EventManager;
import clre20.zeroSentinel.event.EventRecord;
import clre20.zeroSentinel.event.Verdict;
import clre20.zeroSentinel.util.Sync;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 箱子 UI：玩家列表 → 玩家頁（錄製、最近事件）→ 事件頁（錯誤碼、判定）。
 * 只接受左鍵；資料庫讀取一律非同步，完成後若介面仍開著才填入。
 */
public final class GuiManager {

    static final int SIZE = 54;
    static final int PER_PAGE = 45;
    static final int SLOT_PREV = 45;
    static final int SLOT_BACK = 45;
    static final int SLOT_CLOSE = 49;
    static final int SLOT_NEXT = 53;
    static final int SLOT_INFO = 4;
    static final int SLOT_RECORD = 20;
    static final int SLOT_REFRESH = 24;
    static final int EVENTS_START = 27;
    static final int EVENTS_END = 44;
    static final int CODES_START = 18;
    static final int CODES_END = 35;
    static final int SLOT_LEGIT = 38;
    static final int SLOT_RECORDING = 40;
    static final int SLOT_CHEAT = 42;

    private final ZeroSentinel plugin;

    public GuiManager(ZeroSentinel plugin) {
        this.plugin = plugin;
    }

    private MessageManager msg() {
        return plugin.getMessageManager();
    }

    // ---------------- 玩家列表 ----------------

    public void openMain(Player viewer, int page) {
        List<Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (online.size() + PER_PAGE - 1) / PER_PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        ZeroSentinelHolder holder = new ZeroSentinelHolder(plugin, ZeroSentinelHolder.Type.MAIN, p, null, null, 0L);
        Inventory inv = holder.create(SIZE, msg().component("gui.main.title",
                "page", String.valueOf(p + 1), "pages", String.valueOf(pages)));
        int start = p * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < online.size(); i++) {
            Player target = online.get(start + i);
            inv.setItem(i, playerHead(target, "gui.main.player-name", "gui.main.player-lore"));
            holder.setSlotPlayer(i, target.getUniqueId());
        }
        if (p > 0) {
            inv.setItem(SLOT_PREV, item(Material.ARROW, "gui.common.prev", null));
        }
        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, "gui.common.close", null));
        if (p < pages - 1) {
            inv.setItem(SLOT_NEXT, item(Material.ARROW, "gui.common.next", null));
        }
        viewer.openInventory(inv);
    }

    // ---------------- 玩家頁 ----------------

    public void openPlayer(Player viewer, UUID targetId) {
        Player target = plugin.getServer().getPlayer(targetId);
        if (target == null) {
            msg().send(viewer, "gui.player-offline");
            openMain(viewer, 0);
            return;
        }
        ZeroSentinelHolder holder = new ZeroSentinelHolder(plugin, ZeroSentinelHolder.Type.PLAYER, 0,
                targetId, target.getName(), 0L);
        Inventory inv = holder.create(SIZE, msg().component("gui.player.title", "player", target.getName()));
        fillPlayerStatic(inv, target);
        inv.setItem(31, item(Material.HOPPER, "gui.common.loading", null));
        viewer.openInventory(inv);

        UUID viewerId = viewer.getUniqueId();
        plugin.getEventManager().recentEvents(targetId).whenComplete((list, error) -> Sync.run(plugin, () -> {
            Player v = plugin.getServer().getPlayer(viewerId);
            if (v == null || !isOpen(v, holder)) {
                return;
            }
            inv.setItem(31, null);
            if (error != null) {
                inv.setItem(31, item(Material.BARRIER, "gui.common.load-failed", null));
                return;
            }
            if (list.isEmpty()) {
                inv.setItem(31, item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "gui.player.no-events", null));
                return;
            }
            int slot = EVENTS_START;
            for (EventRecord e : list) {
                if (slot > EVENTS_END) {
                    break;
                }
                inv.setItem(slot, eventItem(e));
                holder.setSlotEvent(slot, e.id());
                slot++;
            }
        }));
    }

    private void fillPlayerStatic(Inventory inv, Player target) {
        UUID id = target.getUniqueId();
        var rec = plugin.getRecordingManager();
        String bedrock = msg().raw(rec.isBedrock(id) ? "gui.common.yes" : "gui.common.no");
        int protocolNum = clre20.zeroSentinel.util.Versions.protocol(target);
        String protocol = String.valueOf(protocolNum);
        String versionName = rec.isBedrock(id) || clre20.zeroSentinel.compatibility.FloodgateHook.isBedrock(id)
                ? msg().raw("versions.bedrock-name")
                : java.util.Objects.requireNonNullElse(clre20.zeroSentinel.util.Versions.name(protocolNum),
                msg().raw("versions.unknown", "protocol", protocol));
        String trustScore = String.valueOf(plugin.getTrustManager().score(id));
        long remaining = rec.remainingSeconds(id);
        String status = remaining >= 0
                ? msg().raw("gui.player.status-recording", "seconds", String.valueOf(remaining))
                : msg().raw("gui.player.status-idle");
        inv.setItem(SLOT_INFO, playerHead(target, "gui.player.info-name", "gui.player.info-lore",
                "uuid", id.toString(), "protocol", protocol, "version", versionName, "bedrock", bedrock,
                "status", status, "trust", trustScore));
        if (remaining >= 0) {
            inv.setItem(SLOT_RECORD, item(Material.RED_CONCRETE, "gui.player.record-stop",
                    "gui.player.record-stop-lore", "seconds", String.valueOf(remaining)));
        } else {
            inv.setItem(SLOT_RECORD, item(Material.LIME_CONCRETE, "gui.player.record-start",
                    "gui.player.record-start-lore",
                    "seconds", String.valueOf(plugin.getConfigManager().getGuiRecordSeconds()),
                    "buffer", String.valueOf(plugin.getConfigManager().getBufferSeconds())));
        }
        inv.setItem(SLOT_REFRESH, item(Material.CLOCK, "gui.common.refresh", null));
        inv.setItem(SLOT_BACK, item(Material.ARROW, "gui.common.back", null));
        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, "gui.common.close", null));
    }

    private ItemStack eventItem(EventRecord e) {
        Material m = switch (e.verdict() == null ? Verdict.NONE : e.verdict()) {
            case NONE -> Material.PAPER;
            case LEGIT -> Material.LIME_DYE;
            case CHEAT -> Material.RED_DYE;
        };
        DateTimeFormatter fmt = plugin.getConfigManager().getTimeFormat();
        return item(m, "gui.player.event-name", "gui.player.event-lore",
                "category", e.category(),
                "time", EventFormat.time(fmt, e.created()),
                "event", EventId.format(e.id()),
                "count", String.valueOf(e.violations()),
                "verdict", EventFormat.verdict(msg(), e.verdict()));
    }

    // ---------------- 事件頁 ----------------

    public void openEvent(Player viewer, long eventId, UUID backTarget, String backName) {
        ZeroSentinelHolder holder = new ZeroSentinelHolder(plugin, ZeroSentinelHolder.Type.EVENT, 0,
                backTarget, backName, eventId);
        Inventory inv = holder.create(SIZE, msg().component("gui.event.title", "event", EventId.format(eventId)));
        inv.setItem(SLOT_INFO, item(Material.HOPPER, "gui.common.loading", null));
        inv.setItem(SLOT_BACK, item(Material.ARROW, "gui.common.back", null));
        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, "gui.common.close", null));
        viewer.openInventory(inv);

        UUID viewerId = viewer.getUniqueId();
        plugin.getEventManager().lookupEvent(eventId).whenComplete((opt, error) -> Sync.run(plugin, () -> {
            Player v = plugin.getServer().getPlayer(viewerId);
            if (v == null || !isOpen(v, holder)) {
                return;
            }
            if (error != null) {
                inv.setItem(SLOT_INFO, item(Material.BARRIER, "gui.common.load-failed", null));
                return;
            }
            if (opt.isEmpty()) {
                inv.setItem(SLOT_INFO, item(Material.BARRIER, "gui.event.not-found", null));
                return;
            }
            fillEvent(inv, opt.get());
        }));
    }

    private void fillEvent(Inventory inv, EventManager.Lookup lookup) {
        EventRecord e = lookup.event();
        DateTimeFormatter fmt = plugin.getConfigManager().getTimeFormat();
        inv.setItem(SLOT_INFO, item(Material.BOOK, "gui.event.info-name", "gui.event.info-lore",
                "event", EventId.format(e.id()),
                "player", e.playerName() == null ? "-" : e.playerName(),
                "category", e.category(),
                "created", EventFormat.time(fmt, e.created()),
                "last", EventFormat.time(fmt, e.lastSeen()),
                "count", String.valueOf(e.violations()),
                "verdict", EventFormat.verdict(msg(), e.verdict()),
                "by", e.verdictBy() == null ? "-" : e.verdictBy(),
                "note", e.verdictNote() == null ? "-" : e.verdictNote()));
        int slot = CODES_START;
        for (CodeRecord c : lookup.codes()) {
            if (slot > CODES_END) {
                break;
            }
            inv.setItem(slot++, item(Material.NAME_TAG, "gui.event.code-name", "gui.event.code-lore",
                    "code", c.display(), "time", EventFormat.time(fmt, c.created()), "type", c.type().name()));
        }
        if (e.recording() != null) {
            inv.setItem(SLOT_RECORDING, item(Material.MUSIC_DISC_CAT, "gui.event.recording-name",
                    "gui.event.recording-lore", "file", e.recording()));
        } else {
            inv.setItem(SLOT_RECORDING, item(Material.GRAY_DYE, "gui.event.no-recording", null));
        }
        inv.setItem(SLOT_LEGIT, item(Material.LIME_WOOL, "gui.event.legit", "gui.event.legit-lore"));
        inv.setItem(SLOT_CHEAT, item(Material.RED_WOOL, "gui.event.cheat", "gui.event.cheat-lore"));
    }

    // ---------------- 點擊 ----------------

    void handleClick(Player viewer, ZeroSentinelHolder holder, int slot) {
        if (!viewer.hasPermission(Permissions.GUI)) {
            viewer.closeInventory();
            msg().send(viewer, "command.no-permission");
            return;
        }
        switch (holder.getType()) {
            case MAIN -> clickMain(viewer, holder, slot);
            case PLAYER -> clickPlayer(viewer, holder, slot);
            case EVENT -> clickEvent(viewer, holder, slot);
        }
    }

    private void clickMain(Player viewer, ZeroSentinelHolder holder, int slot) {
        if (slot == SLOT_PREV && holder.getPage() > 0) {
            openMain(viewer, holder.getPage() - 1);
        } else if (slot == SLOT_NEXT) {
            openMain(viewer, holder.getPage() + 1);
        } else if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
        } else if (slot < PER_PAGE) {
            UUID target = holder.getSlotPlayer(slot);
            if (target != null) {
                openPlayer(viewer, target);
            }
        }
    }

    private void clickPlayer(Player viewer, ZeroSentinelHolder holder, int slot) {
        UUID targetId = holder.getTarget();
        if (slot == SLOT_BACK) {
            openMain(viewer, 0);
        } else if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
        } else if (slot == SLOT_REFRESH) {
            openPlayer(viewer, targetId);
        } else if (slot == SLOT_RECORD) {
            if (!viewer.hasPermission(Permissions.RECORD)) {
                msg().send(viewer, "command.no-permission");
                return;
            }
            Player target = plugin.getServer().getPlayer(targetId);
            if (target == null) {
                msg().send(viewer, "gui.player-offline");
                openMain(viewer, 0);
                return;
            }
            if (plugin.getRecordingManager().isRecording(targetId)) {
                plugin.getRecordingManager().stop(targetId);
                msg().send(viewer, "record.stopped", "player", target.getName());
            } else {
                ZeroSentinelCommand.startRecording(plugin, viewer, target, plugin.getConfigManager().getGuiRecordSeconds());
            }
            fillPlayerStatic(holder.getInventory(), target);
        } else if (slot >= EVENTS_START && slot <= EVENTS_END) {
            long id = holder.getSlotEvent(slot);
            if (id != 0L) {
                openEvent(viewer, id, targetId, holder.getTargetName());
            }
        }
    }

    private void clickEvent(Player viewer, ZeroSentinelHolder holder, int slot) {
        if (slot == SLOT_BACK) {
            UUID back = holder.getTarget();
            if (back != null && plugin.getServer().getPlayer(back) != null) {
                openPlayer(viewer, back);
            } else {
                openMain(viewer, 0);
            }
        } else if (slot == SLOT_CLOSE) {
            viewer.closeInventory();
        } else if (slot == SLOT_LEGIT || slot == SLOT_CHEAT) {
            if (!viewer.hasPermission(Permissions.VERDICT)) {
                msg().send(viewer, "command.no-permission");
                return;
            }
            if (holder.isBusy()) {
                return;
            }
            holder.setBusy(true);
            Verdict verdict = slot == SLOT_LEGIT ? Verdict.LEGIT : Verdict.CHEAT;
            UUID viewerId = viewer.getUniqueId();
            long eventId = holder.getEventId();
            plugin.getVerdictService().judge(eventId, verdict, viewer.getName(), null)
                    .whenComplete((opt, error) -> Sync.run(plugin, () -> {
                        holder.setBusy(false);
                        Player v = plugin.getServer().getPlayer(viewerId);
                        if (v == null) {
                            return;
                        }
                        if (error != null) {
                            msg().send(v, "command.database-error");
                            return;
                        }
                        if (opt.isEmpty()) {
                            msg().send(v, "verdict.not-found");
                            return;
                        }
                        msg().send(v, "verdict.done", "event", EventId.format(eventId),
                                "verdict", EventFormat.verdict(msg(), verdict));
                        if (opt.get().refund() > 0) {
                            msg().send(v, "verdict.refunded", "player", String.valueOf(opt.get().event().playerName()),
                                    "refund", String.valueOf(opt.get().refund()));
                        }
                        if (isOpen(v, holder)) {
                            openEvent(v, eventId, holder.getTarget(), holder.getTargetName());
                        }
                    }));
        }
    }

    // ---------------- 工具 ----------------

    private static boolean isOpen(Player viewer, ZeroSentinelHolder holder) {
        InventoryView view = viewer.getOpenInventory();
        return view.getTopInventory().getHolder(false) == holder;
    }

    /** 關閉所有開著的 ZeroSentinel 介面（插件停用時） */
    public void closeAll() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof ZeroSentinelHolder) {
                p.closeInventory();
            }
        }
    }

    private ItemStack item(Material material, String nameKey, String loreKey, String... placeholders) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(msg().item(nameKey, placeholders));
            if (loreKey != null) {
                meta.lore(msg().itemLore(loreKey, placeholders));
            }
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack playerHead(Player target, String nameKey, String loreKey, String... extra) {
        String[] placeholders = new String[extra.length + 2];
        placeholders[0] = "player";
        placeholders[1] = target.getName();
        System.arraycopy(extra, 0, placeholders, 2, extra.length);
        ItemStack stack = item(Material.PLAYER_HEAD, nameKey, loreKey, placeholders);
        if (stack.getItemMeta() instanceof SkullMeta skull) {
            // 線上玩家的資料已在記憶體中，不會觸發網路查詢
            skull.setPlayerProfile(target.getPlayerProfile());
            stack.setItemMeta(skull);
        }
        return stack;
    }
}
