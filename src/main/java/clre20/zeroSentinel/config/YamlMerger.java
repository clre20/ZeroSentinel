package clre20.zeroSentinel.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

/**
 * 讀取資料夾中的 YAML，並與 jar 內預設檔比對，自動補齊缺失項目（保留使用者原值）。
 */
public final class YamlMerger {

    private YamlMerger() {
    }

    /**
     * @param plugin       插件
     * @param fileName     檔名（同時是 jar 內資源名）
     * @param freeSections 這些區段若使用者已存在，就不再往下補齊子項（讓使用者可自由刪除子項，例如自訂動作組）
     * @return 合併後的設定
     */
    public static YamlConfiguration loadAndMerge(JavaPlugin plugin, String fileName, List<String> freeSections) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }

        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            // 檔案壞掉：備份後改用預設檔，避免整個插件無法啟動
            plugin.getLogger().log(Level.SEVERE, fileName + " 格式錯誤，已備份為 " + fileName + ".broken 並使用預設值。", e);
            File broken = new File(plugin.getDataFolder(), fileName + ".broken");
            if (broken.exists() && !broken.delete()) {
                plugin.getLogger().warning("無法刪除舊的 " + broken.getName());
            }
            if (!file.renameTo(broken)) {
                plugin.getLogger().warning("無法備份 " + fileName);
            }
            plugin.saveResource(fileName, true);
            user = new YamlConfiguration();
            try {
                user.load(file);
            } catch (IOException | InvalidConfigurationException ex) {
                plugin.getLogger().log(Level.SEVERE, "無法讀取預設 " + fileName, ex);
            }
        }

        YamlConfiguration defaults = loadDefaults(plugin, fileName);
        if (defaults == null) {
            return user;
        }

        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (isUnderFreeSection(user, key, freeSections)) {
                continue;
            }
            if (defaults.isConfigurationSection(key)) {
                if (!user.isConfigurationSection(key) && !user.contains(key)) {
                    user.createSection(key);
                    copyComments(defaults, user, key);
                    changed = true;
                }
                continue;
            }
            if (!user.contains(key, true)) {
                user.set(key, defaults.get(key));
                copyComments(defaults, user, key);
                changed = true;
            }
        }

        if (changed) {
            try {
                user.save(file);
                plugin.getLogger().info(fileName + " 已自動補齊缺失的設定項目。");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "無法寫回 " + fileName, e);
            }
        }
        user.setDefaults(defaults);
        return user;
    }

    private static boolean isUnderFreeSection(YamlConfiguration user, String key, List<String> freeSections) {
        for (String section : freeSections) {
            if (key.startsWith(section + ".") && user.isConfigurationSection(section)) {
                ConfigurationSection existing = user.getConfigurationSection(section);
                if (existing != null && !existing.getKeys(false).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void copyComments(YamlConfiguration from, YamlConfiguration to, String key) {
        List<String> comments = from.getComments(key);
        if (!comments.isEmpty()) {
            to.setComments(key, comments);
        }
        List<String> inline = from.getInlineComments(key);
        if (!inline.isEmpty()) {
            to.setInlineComments(key, inline);
        }
    }

    private static YamlConfiguration loadDefaults(JavaPlugin plugin, String fileName) {
        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "無法讀取內建 " + fileName, e);
            return null;
        }
    }
}
