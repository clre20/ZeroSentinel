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
    private static final Set<PacketType.Play.Client> MOVEMENT = EnumSet.of(
            PacketType.Play.Client.CLIENT_TICK_END,
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_FLYING,
            PacketType.Play.Client.VEHICLE_MOVE,
            PacketType.Play.Client.STEER_BOAT
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
        data.onPacket(System.nanoTime(), MOVEMENT.contains(client), client == PacketType.Play.Client.PLAYER_LOADED);
    }
}
