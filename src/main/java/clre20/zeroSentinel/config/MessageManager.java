package clre20.zeroSentinel.config;

import clre20.zeroSentinel.ZeroSentinel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * messages.yml 讀取。所有顯示文字都從這裡取得，支援 &amp; 色碼與 {變數} 替換。
 */
public final class MessageManager {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final ZeroSentinel plugin;
    private Map<String, String> strings = new HashMap<>();
    private Map<String, List<String>> lists = new HashMap<>();
    private String prefix = "";

    public MessageManager(ZeroSentinel plugin) {
        this.plugin = plugin;
    }

    public void load() {
        YamlConfiguration cfg = YamlMerger.loadAndMerge(plugin, "messages.yml", List.of());
        Map<String, String> s = new HashMap<>();
        Map<String, List<String>> l = new HashMap<>();
        for (String key : cfg.getKeys(true)) {
            if (cfg.isList(key)) {
                l.put(key, List.copyOf(cfg.getStringList(key)));
            } else if (cfg.isString(key)) {
                s.put(key, cfg.getString(key, ""));
            }
        }
        strings = s;
        lists = l;
        prefix = s.getOrDefault("prefix", "");
    }

    /**
     * 取得原始字串（已替換變數，未轉換色碼）。找不到 key 時回傳 key 本身，避免 NPE。
     */
    public String raw(String key, String... placeholders) {
        String text = strings.get(key);
        if (text == null) {
            text = key;
        }
        return apply(text, placeholders);
    }

    public Component component(String key, String... placeholders) {
        return SERIALIZER.deserialize(raw(key, placeholders));
    }

    /**
     * 給物品名稱 / Lore 使用：關閉預設斜體。
     */
    public Component item(String key, String... placeholders) {
        return component(key, placeholders).decoration(TextDecoration.ITALIC, false);
    }

    public List<Component> itemLore(String key, String... placeholders) {
        List<String> source = lists.get(key);
        List<Component> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (String line : source) {
            out.add(SERIALIZER.deserialize(apply(line, placeholders)).decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }

    public void send(CommandSender sender, String key, String... placeholders) {
        String text = raw(key, placeholders);
        if (text.isEmpty()) {
            return;
        }
        sender.sendMessage(SERIALIZER.deserialize(prefix + text));
    }

    /** 不加前綴（給被偵測的玩家看的訊息使用） */
    public void sendNoPrefix(CommandSender sender, String key, String... placeholders) {
        String text = raw(key, placeholders);
        if (text.isEmpty()) {
            return;
        }
        sender.sendMessage(SERIALIZER.deserialize(text));
    }

    /**
     * 給玩家輸入的文字使用：去除 &amp; 色碼，避免玩家名稱或參數夾帶格式。
     */
    public static String stripInput(String input) {
        return input == null ? "" : input.replace('&', ' ').replace('§', ' ');
    }

    private static String apply(String text, String... placeholders) {
        if (placeholders.length < 2) {
            return text;
        }
        String result = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1] == null ? "" : placeholders[i + 1];
            result = result.replace("{" + placeholders[i] + "}", value);
        }
        return result;
    }
}
