package clre20.zeroSentinel.util;

import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/** 從背景執行緒切回主執行緒（插件已停用時直接忽略） */
public final class Sync {

    private Sync() {
    }

    public static void run(Plugin plugin, Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "執行回呼時發生錯誤", e);
                }
            });
        } catch (RuntimeException ignored) {
            // 插件正在停用
        }
    }
}
