package clre20.zeroSentinel.check;

import clre20.zeroSentinel.ZeroSentinel;
import clre20.zeroSentinel.config.ConfigManager;
import clre20.zeroSentinel.util.Sync;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEditBook;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientNameItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;

import java.util.List;
import java.util.UUID;

/**
 * 封包層檢查（防崩服與異常封包）＋ transaction 的 PONG 接收與伺服器狀態追蹤。
 * <ul>
 *     <li>接收：LOW 優先度（需要時取消封包）；只解析少數類型，其他封包立即返回。</li>
 *     <li>送出：MONITOR 優先度，只讀取伺服器送出的權限、傳送、手持格。</li>
 *     <li>解析封包後一律把讀取位置還原，不影響錄製與其他插件。</li>
 *     <li>回報經節流後才切回主執行緒，惡意大量送出異常封包也不會塞爆主執行緒。</li>
 * </ul>
 */
public final class PacketCheckListener {

    private static final int CHANNEL_OVERHEAD = 260;

    private final ZeroSentinel plugin;
    private final PlayerDataManager players;
    private final TransactionManager transactions;
    private PacketListenerCommon receive;
    private PacketListenerCommon send;

    public PacketCheckListener(ZeroSentinel plugin, PlayerDataManager players, TransactionManager transactions) {
        this.plugin = plugin;
        this.players = players;
        this.transactions = transactions;
    }

    public void register() {
        receive = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                handleReceive(event);
            }
        }, PacketListenerPriority.LOW);
        send = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                handleSend(event);
            }
        }, PacketListenerPriority.MONITOR);
    }

    public void unregister() {
        if (receive != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(receive);
        }
        if (send != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(send);
        }
    }

    private PlayerData data(User user) {
        if (user == null) {
            return null;
        }
        UUID uuid = user.getUUID();
        return uuid == null ? null : players.get(uuid);
    }

    // ---------------- 接收 ----------------

    private void handleReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!(type instanceof PacketType.Play.Client client)) {
            return;
        }
        switch (client) {
            case PONG, PLAYER_POSITION, PLAYER_POSITION_AND_ROTATION, PLAYER_ROTATION, HELD_ITEM_CHANGE,
                 ENTITY_ACTION, TELEPORT_CONFIRM, PLAYER_ABILITIES, CREATIVE_INVENTORY_ACTION, ATTACK,
                 INTERACT_ENTITY, NAME_ITEM, TAB_COMPLETE, PLUGIN_MESSAGE, EDIT_BOOK, UPDATE_SIGN -> {
                // 需要檢查的類型
            }
            default -> {
                return;
            }
        }
        PlayerData d = data(event.getUser());
        if (d == null) {
            return;
        }
        ConfigManager c = plugin.getConfigManager();
        Object buf = event.getByteBuf();
        int readerIndex = buf != null ? ByteBufHelper.readerIndex(buf) : 0;
        try {
            if (client == PacketType.Play.Client.PONG) {
                int id = new WrapperPlayClientPong(event).getId();
                if (TransactionState.isOurs(id)) {
                    d.transaction().onPong(id, System.nanoTime());
                    // 自己送出的 PING 的回應，不需要交給伺服器
                    event.setCancelled(true);
                }
                return;
            }
            if (!c.isPacketChecksEnabled()) {
                return;
            }
            switch (client) {
                case PLAYER_POSITION, PLAYER_POSITION_AND_ROTATION, PLAYER_ROTATION -> {
                    WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);
                    Location loc = w.getLocation();
                    if (w.hasPositionChanged() && PacketRule.invalidPosition(loc.getX(), loc.getY(), loc.getZ())) {
                        flag(event, d, PacketRule.INVALID_POSITION, "");
                    }
                    if (w.hasRotationChanged() && PacketRule.invalidRotation(loc.getYaw(), loc.getPitch())) {
                        flag(event, d, PacketRule.INVALID_ROTATION, String.format("%.1f", loc.getPitch()));
                    }
                }
                case HELD_ITEM_CHANGE -> {
                    int slot = new WrapperPlayClientHeldItemChange(event).getSlot();
                    PacketRule r = d.packets().onHeldSlot(slot);
                    if (r != null) {
                        flag(event, d, r, String.valueOf(slot));
                    }
                }
                case ENTITY_ACTION -> {
                    WrapperPlayClientEntityAction.Action a = new WrapperPlayClientEntityAction(event).getAction();
                    if (a == WrapperPlayClientEntityAction.Action.START_SPRINTING
                            || a == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
                        PacketRule r = d.packets().onSprint(a == WrapperPlayClientEntityAction.Action.START_SPRINTING);
                        if (r != null) {
                            flag(event, d, r, "");
                        }
                    }
                }
                case TELEPORT_CONFIRM -> {
                    int id = new WrapperPlayClientTeleportConfirm(event).getTeleportId();
                    PacketRule r = d.packets().onTeleportConfirm(id);
                    if (r != null) {
                        flag(event, d, r, String.valueOf(id));
                    }
                }
                case PLAYER_ABILITIES -> {
                    if (new WrapperPlayClientPlayerAbilities(event).isFlying() && !d.transaction().mayFly()) {
                        flag(event, d, PacketRule.ILLEGAL_FLIGHT, "");
                    }
                }
                case CREATIVE_INVENTORY_ACTION -> {
                    if (!d.transaction().mayCreative()) {
                        flag(event, d, PacketRule.ILLEGAL_CREATIVE, "");
                    }
                }
                case ATTACK -> {
                    if (new WrapperPlayClientAttack(event).getEntityId() == d.entityId()) {
                        flag(event, d, PacketRule.SELF_INTERACT, "");
                    }
                }
                case INTERACT_ENTITY -> {
                    if (new WrapperPlayClientInteractEntity(event).getEntityId() == d.entityId()) {
                        flag(event, d, PacketRule.SELF_INTERACT, "");
                    }
                }
                case NAME_ITEM -> {
                    String name = new WrapperPlayClientNameItem(event).getItemName();
                    if (name != null && name.length() > c.getLimitNameItem()) {
                        flag(event, d, PacketRule.OVERSIZED_NAME, String.valueOf(name.length()));
                    }
                }
                case TAB_COMPLETE -> {
                    String text = new WrapperPlayClientTabComplete(event).getText();
                    if (text != null && text.length() > c.getLimitTabComplete()) {
                        flag(event, d, PacketRule.OVERSIZED_TAB_COMPLETE, String.valueOf(text.length()));
                    }
                }
                case PLUGIN_MESSAGE -> {
                    // 可讀長度包含頻道名稱（最多約 260 bytes），上限設定只算內容
                    int size = buf != null ? ByteBufHelper.readableBytes(buf) : 0;
                    if (size > c.getLimitPluginMessage() + CHANNEL_OVERHEAD) {
                        flag(event, d, PacketRule.OVERSIZED_PAYLOAD, String.valueOf(size));
                    }
                }
                case EDIT_BOOK -> {
                    WrapperPlayClientEditBook w = new WrapperPlayClientEditBook(event);
                    List<String> pages = w.getPages();
                    String title = w.getTitle();
                    boolean bad = pages != null && pages.size() > c.getLimitBookPages();
                    if (!bad && pages != null) {
                        for (String p : pages) {
                            if (p != null && p.length() > c.getLimitBookPageChars()) {
                                bad = true;
                                break;
                            }
                        }
                    }
                    if (!bad && title != null && title.length() > c.getLimitBookTitle()) {
                        bad = true;
                    }
                    if (bad) {
                        flag(event, d, PacketRule.OVERSIZED_BOOK, String.valueOf(pages == null ? 0 : pages.size()));
                    }
                }
                case UPDATE_SIGN -> {
                    String[] lines = new WrapperPlayClientUpdateSign(event).getTextLines();
                    if (lines != null) {
                        for (String l : lines) {
                            if (l != null && l.length() > c.getLimitSignLine()) {
                                flag(event, d, PacketRule.OVERSIZED_SIGN, String.valueOf(l.length()));
                                break;
                            }
                        }
                    }
                }
                default -> {
                    // 不會到這裡
                }
            }
        } catch (RuntimeException e) {
            // 無法解析的封包交給伺服器原本的處理（伺服器會自行拒絕格式錯誤的封包）
        } finally {
            if (buf != null) {
                ByteBufHelper.readerIndex(buf, readerIndex);
            }
        }
    }

    private void flag(PacketReceiveEvent event, PlayerData d, PacketRule rule, String detail) {
        ConfigManager c = plugin.getConfigManager();
        if (!rule.crash() && d.bedrock() && !c.isPacketChecksBedrock()) {
            return;
        }
        if (c.isPacketRuleDisabled(rule)) {
            return;
        }
        if (rule.cancel() && c.isPacketChecksCancel()) {
            event.setCancelled(true);
        }
        int count = d.packets().report(rule, System.nanoTime(), c.getPacketAlertIntervalSeconds() * 1_000_000_000L);
        if (count > 0) {
            UUID uuid = d.uuid();
            String name = d.name();
            Sync.run(plugin, () -> plugin.getPunishmentManager().packetAlert(uuid, name, rule, count, detail));
        }
    }

    // ---------------- 送出 ----------------

    private void handleSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.PLAYER_ABILITIES
                && type != PacketType.Play.Server.PLAYER_POSITION_AND_LOOK
                && type != PacketType.Play.Server.HELD_ITEM_CHANGE) {
            return;
        }
        PlayerData d = data(event.getUser());
        if (d == null) {
            return;
        }
        Object buf = event.getByteBuf();
        int readerIndex = buf != null ? ByteBufHelper.readerIndex(buf) : 0;
        try {
            if (type == PacketType.Play.Server.PLAYER_ABILITIES) {
                WrapperPlayServerPlayerAbilities w = new WrapperPlayServerPlayerAbilities(event);
                d.transaction().serverAbilities(w.isFlightAllowed(), w.isInCreativeMode());
                // 緊接著送一個 PING：客戶端回應它，就代表已收到新的權限
                event.getTasksAfterSend().add(() -> transactions.send(d));
            } else if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
                d.packets().onTeleportSent(new WrapperPlayServerPlayerPositionAndLook(event).getTeleportId());
            } else {
                d.packets().onServerHeldSlot();
            }
        } catch (RuntimeException ignored) {
            // 無法解析時不影響送出
        } finally {
            if (buf != null) {
                ByteBufHelper.readerIndex(buf, readerIndex);
            }
        }
    }
}
