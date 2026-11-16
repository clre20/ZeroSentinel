package clre20.zeroSentinel.compatibility;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Floodgate 軟相依：判斷玩家是否為 Geyser 基岩版玩家。
 * 用反射呼叫 {@code FloodgateApi.getInstance().isFloodgatePlayer(UUID)}，沒安裝 Floodgate 時一律回傳 false。
 * 不使用名稱前綴判斷（可被偽造）。
 */
public final class FloodgateHook {

    private static volatile Object api;
    private static volatile Method isFloodgatePlayer;

    private FloodgateHook() {
    }

    public static void init(Logger logger) {
        api = null;
        isFloodgatePlayer = null;
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            return;
        }
        try {
            Class<?> cls = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = cls.getMethod("getInstance").invoke(null);
            Method m = cls.getMethod("isFloodgatePlayer", UUID.class);
            api = instance;
            isFloodgatePlayer = m;
            logger.info("已連結 Floodgate，可辨識基岩版玩家。");
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("無法連結 Floodgate API：" + e.getMessage());
        }
    }

    public static boolean isAvailable() {
        return api != null && isFloodgatePlayer != null;
    }

    public static boolean isBedrock(UUID uuid) {
        Object a = api;
        Method m = isFloodgatePlayer;
        if (a == null || m == null || uuid == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(m.invoke(a, uuid));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
