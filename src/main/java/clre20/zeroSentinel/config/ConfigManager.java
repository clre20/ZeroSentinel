package clre20.zeroSentinel.config;

import clre20.zeroSentinel.ZeroSentinel;
import org.bukkit.configuration.file.YamlConfiguration;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * config.yml 讀取與防呆。所有值在讀取時就限制範圍，執行期間直接讀欄位，不再碰檔案。
 * 欄位皆為 volatile：重載時由主執行緒寫入，netty 與背景執行緒讀取。
 */
public final class ConfigManager {

    private final ZeroSentinel plugin;

    // 錄製
    private volatile boolean recordEnabled = true;
    private volatile int bufferBytes = 256 * 1024;
    private volatile int bufferSeconds = 10;
    private volatile int maxPacketBytes = 32 * 1024;
    private volatile boolean recordOnFlag = true;
    private volatile int afterFlagSeconds = 5;
    private volatile int manualMaxSeconds = 300;
    private volatile long maxSessionBytes = 32L * 1024 * 1024;

    // 儲存
    private volatile long recordingsMaxTotalBytes = 2048L * 1024 * 1024;
    private volatile int recordingsRetentionDays = 30;
    private volatile int dbFlushSeconds = 5;
    private volatile int dbBatchSize = 500;
    private volatile int dbCacheSize = 2000;
    private volatile int dbRetentionDays = 30;

    // 事件
    private volatile int mergeWindowSeconds = 60;

    // 介面與顯示
    private volatile int guiRecordSeconds = 60;
    private volatile DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 其他
    private volatile boolean debugEnabled = false;
    private volatile boolean updateCheckOnStartup = true;
    private volatile boolean updateNotifyOnJoin = true;

    public ConfigManager(ZeroSentinel plugin) {
        this.plugin = plugin;
    }

    public void load() {
        YamlConfiguration cfg = YamlMerger.loadAndMerge(plugin, "config.yml", List.of());

        recordEnabled = cfg.getBoolean("record.enabled", true);
        bufferBytes = clamp(cfg.getInt("record.buffer-kb", 256), 32, 4096) * 1024;
        bufferSeconds = clamp(cfg.getInt("record.buffer-seconds", 10), 1, 60);
        maxPacketBytes = clamp(cfg.getInt("record.max-packet-kb", 32), 1, 1024) * 1024;
        recordOnFlag = cfg.getBoolean("record.on-flag", true);
        afterFlagSeconds = clamp(cfg.getInt("record.after-flag-seconds", 5), 0, 60);
        manualMaxSeconds = clamp(cfg.getInt("record.manual-max-seconds", 300), 1, 3600);
        maxSessionBytes = clamp(cfg.getInt("record.max-session-mb", 32), 1, 1024) * 1024L * 1024L;

        recordingsMaxTotalBytes = clamp(cfg.getInt("storage.recordings.max-total-mb", 2048), 0, 1_048_576) * 1024L * 1024L;
        recordingsRetentionDays = clamp(cfg.getInt("storage.recordings.retention-days", 30), 0, 3650);
        dbFlushSeconds = clamp(cfg.getInt("storage.database.flush-seconds", 5), 1, 300);
        dbBatchSize = clamp(cfg.getInt("storage.database.batch-size", 500), 10, 10_000);
        dbCacheSize = clamp(cfg.getInt("storage.database.cache-size", 2000), 100, 100_000);
        dbRetentionDays = clamp(cfg.getInt("storage.database.retention-days", 30), 0, 3650);

        mergeWindowSeconds = clamp(cfg.getInt("events.merge-window-seconds", 60), 1, 3600);

        guiRecordSeconds = clamp(cfg.getInt("gui.record-seconds", 60), 1, 3600);
        String pattern = cfg.getString("display.time-format", "yyyy-MM-dd HH:mm:ss");
        try {
            timeFormat = DateTimeFormatter.ofPattern(pattern == null ? "yyyy-MM-dd HH:mm:ss" : pattern);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("display.time-format 格式錯誤，改用預設值。");
            timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        }

        debugEnabled = cfg.getBoolean("debug.enabled", false);
        updateCheckOnStartup = cfg.getBoolean("update-checker.check-on-startup", true);
        updateNotifyOnJoin = cfg.getBoolean("update-checker.notify-on-join", true);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean isRecordEnabled() {
        return recordEnabled;
    }

    public int getBufferBytes() {
        return bufferBytes;
    }

    public int getBufferSeconds() {
        return bufferSeconds;
    }

    public int getMaxPacketBytes() {
        return maxPacketBytes;
    }

    public boolean isRecordOnFlag() {
        return recordOnFlag;
    }

    public int getAfterFlagSeconds() {
        return afterFlagSeconds;
    }

    public int getManualMaxSeconds() {
        return manualMaxSeconds;
    }

    public long getMaxSessionBytes() {
        return maxSessionBytes;
    }

    public long getRecordingsMaxTotalBytes() {
        return recordingsMaxTotalBytes;
    }

    public int getRecordingsRetentionDays() {
        return recordingsRetentionDays;
    }

    public int getDbFlushSeconds() {
        return dbFlushSeconds;
    }

    public int getDbBatchSize() {
        return dbBatchSize;
    }

    public int getDbCacheSize() {
        return dbCacheSize;
    }

    public int getDbRetentionDays() {
        return dbRetentionDays;
    }

    public int getMergeWindowSeconds() {
        return mergeWindowSeconds;
    }

    public int getGuiRecordSeconds() {
        return Math.min(guiRecordSeconds, manualMaxSeconds);
    }

    public DateTimeFormatter getTimeFormat() {
        return timeFormat;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public boolean isUpdateCheckOnStartup() {
        return updateCheckOnStartup;
    }

    public boolean isUpdateNotifyOnJoin() {
        return updateNotifyOnJoin;
    }
}
