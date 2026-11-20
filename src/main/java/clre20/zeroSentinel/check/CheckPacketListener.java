package clre20.zeroSentinel.check;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * 檢查用的封包監聽（netty 執行緒，LOWEST 優先度，看到的是其他插件處理前的原始封包）。
 * 只更新時間戳，不做任何耗時運算。
 * 封包類型由 PacketEvents 依「伺服器實際收到的版本」判斷，ViaBackwards 連入的舊版客戶端也正確。
 */
public final class CheckPacketListener implements PacketListener {

    /** 移動與 tick 結束類：正常客戶端每秒至少會送 1 個 */
    static final Set<PacketType.Play.Client> MOVEMENT = EnumSet.of(
            PacketType.Play.Client.CLIENT_TICK_END,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_FLYING,
            PacketType.Play.Client.VEHICLE_MOVE,
            PacketType.Play.Client.STEER_BOAT
    );

    /**
     * 需要遊戲主程式才會送出的封包（玩家的操作）。客戶端卡住時只剩網路層的 keep-alive / PONG 等，送不出這些。
     * 不包含進服時自動送出的封包（設定、載入完成、資源包回應、區塊確認、插件訊息等）。
     */
    static final Set<PacketType.Play.Client> ACTIVE = EnumSet.of(
            PacketType.Play.Client.CHAT_MESSAGE,
            PacketType.Play.Client.CHAT_COMMAND,
            PacketType.Play.Client.CHAT_COMMAND_UNSIGNED,
            PacketType.Play.Client.TAB_COMPLETE,
            PacketType.Play.Client.CLICK_WINDOW,
            PacketType.Play.Client.CLICK_WINDOW_BUTTON,
            PacketType.Play.Client.CLOSE_WINDOW,
            PacketType.Play.Client.CREATIVE_INVENTORY_ACTION,
            PacketType.Play.Client.INTERACT_ENTITY,
            PacketType.Play.Client.ATTACK,
            PacketType.Play.Client.PUNCH,
            PacketType.Play.Client.ANIMATION,
            PacketType.Play.Client.PLAYER_DIGGING,
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            PacketType.Play.Client.USE_ITEM,
            PacketType.Play.Client.HELD_ITEM_CHANGE,
            PacketType.Play.Client.ENTITY_ACTION,
            PacketType.Play.Client.PLAYER_INPUT,
            PacketType.Play.Client.PLAYER_ABILITIES,
            PacketType.Play.Client.PICK_ITEM,
            PacketType.Play.Client.PICK_ITEM_FROM_BLOCK,
            PacketType.Play.Client.PICK_ITEM_FROM_ENTITY,
            PacketType.Play.Client.NAME_ITEM,
            PacketType.Play.Client.SELECT_TRADE,
            PacketType.Play.Client.EDIT_BOOK,
            PacketType.Play.Client.UPDATE_SIGN,
            PacketType.Play.Client.SELECT_BUNDLE_ITEM
    );

    private final PlayerDataManager players;

    public CheckPacketListener(PlayerDataManager players) {
        this.players = players;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!(type instanceof PacketType.Play.Client client)) {
            return;
        }
        // transaction 的 PONG 每秒都會回來，不能拿來判斷「連線還活著」，否則會改變 ClientReport 的判定
        if (client == PacketType.Play.Client.PONG) {
            return;
        }
        User user = event.getUser();
        if (user == null) {
            return;
        }
        UUID uuid = user.getUUID();
        if (uuid == null) {
            return;
        }
        PlayerData data = players.get(uuid);
        if (data == null) {
            return;
        }
        data.onPacket(System.nanoTime(), MOVEMENT.contains(client), client == PacketType.Play.Client.PLAYER_LOADED,
                ACTIVE.contains(client));
    }
}
