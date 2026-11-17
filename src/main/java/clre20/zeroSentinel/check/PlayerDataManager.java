package clre20.zeroSentinel.check;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 線上玩家的檢查狀態（進服建立、離線移除） */
public final class PlayerDataManager {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();

    public PlayerData get(UUID uuid) {
        return data.get(uuid);
    }

    public void put(PlayerData d) {
        data.put(d.uuid(), d);
    }

    public void remove(UUID uuid) {
        data.remove(uuid);
    }

    public Collection<PlayerData> all() {
        return data.values();
    }

    public void clear() {
        data.clear();
    }
}
