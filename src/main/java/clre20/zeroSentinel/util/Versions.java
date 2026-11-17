package clre20.zeroSentinel.util;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.entity.Player;

/** 客戶端版本工具 */
public final class Versions {

    private Versions() {
    }

    /** 玩家實際使用的客戶端協議版本（經 ViaVersion / ViaBackwards 也會是原始版本）；取不到時回傳 -1 */
    public static int protocol(Player player) {
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user != null && user.getClientVersion() != null) {
                return user.getClientVersion().getProtocolVersion();
            }
        } catch (RuntimeException ignored) {
            // 取不到時回傳 -1
        }
        return -1;
    }

    /** 協議版本轉成版本名稱，例如 777 → 26.3；無法辨識時回傳 null */
    public static String name(int protocol) {
        if (protocol < 0) {
            return null;
        }
        try {
            ClientVersion v = ClientVersion.getById(protocol);
            if (v == null || v == ClientVersion.UNKNOWN || v.getReleaseName() == null) {
                return null;
            }
            return v.getReleaseName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
