package clre20.zeroSentinel;

import clre20.zeroSentinel.check.CheckPacketListener;
import clre20.zeroSentinel.check.ClientReportCheck;
import clre20.zeroSentinel.check.PlayerDataManager;
import clre20.zeroSentinel.command.ZeroSentinelCommand;
import clre20.zeroSentinel.compatibility.FloodgateHook;
import clre20.zeroSentinel.config.ConfigManager;
import clre20.zeroSentinel.config.MessageManager;
import clre20.zeroSentinel.event.EventManager;
import clre20.zeroSentinel.gui.GuiListener;
import clre20.zeroSentinel.gui.GuiManager;
import clre20.zeroSentinel.lag.LagMonitor;
import clre20.zeroSentinel.punish.PunishmentManager;
import clre20.zeroSentinel.trust.TrustManager;
import clre20.zeroSentinel.util.Versions;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import clre20.zeroSentinel.listener.PlayerListener;
import clre20.zeroSentinel.record.RecordingManager;
import clre20.zeroSentinel.record.RecordingSession;
import clre20.zeroSentinel.storage.Database;
import clre20.zeroSentinel.update.UpdateChecker;
import clre20.zeroSentinel.update.UpdateListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

/**
 * ZeroSentinel 主類別。
 */
public final class ZeroSentinel extends JavaPlugin {

    private static final long DAY_TICKS = 20L * 60 * 60 * 24;

    private ConfigManager configManager;
    private MessageManager messageManager;
    private Database database;
    private EventManager eventManager;
    private RecordingManager recordingManager;
    private UpdateChecker updateChecker;
    private GuiManager guiManager;
    private BukkitTask tickTask;
    private BukkitTask cleanupTask;
    private BukkitTask minuteTask;
    private PlayerDataManager playerDataManager;
    private TrustManager trustManager;
    private PunishmentManager punishmentManager;
    private clre20.zeroSentinel.punish.VerdictService verdictService;
    private LagMonitor lagMonitor;
    private ClientReportCheck clientReportCheck;
    private PacketListenerCommon checkListener;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();
        messageManager = new MessageManager(this);
        messageManager.load();

        FloodgateHook.init(getLogger());

        database = new Database(getDataFolder().toPath().resolve("data.db"), getLogger(),
                configManager.getDbFlushSeconds(), configManager.getDbBatchSize());
        try {
            database.start();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "無法開啟資料庫，插件停用。", e);
            database.close(5);
            database = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        eventManager = new EventManager(database, configManager.getDbCacheSize(),
                () -> configManager.getMergeWindowSeconds());

        trustManager = new TrustManager(database, configManager, getLogger());
        punishmentManager = new PunishmentManager(this);
        verdictService = new clre20.zeroSentinel.punish.VerdictService(eventManager, trustManager);
        playerDataManager = new PlayerDataManager();
        lagMonitor = new LagMonitor(getLogger(), () -> configManager.getLagStallThresholdMs(),
                () -> configManager.getLagGraceSeconds() * 1000L);
        lagMonitor.start();
        clientReportCheck = new ClientReportCheck(this, playerDataManager);

        recordingManager = new RecordingManager(this, configManager,
                getDataFolder().toPath().resolve("recordings"), this::onRecordingFinished);
        recordingManager.start();

        guiManager = new GuiManager(this);

        checkListener = PacketEvents.getAPI().getEventManager().registerListener(
                new CheckPacketListener(playerDataManager), PacketListenerPriority.LOWEST);
        // 插件重載時已在線上的玩家
        for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
            playerDataManager.put(new clre20.zeroSentinel.check.PlayerData(p.getUniqueId(), p.getName(),
                    FloodgateHook.isBedrock(p.getUniqueId()), Versions.protocol(p), System.nanoTime(),
                    configManager.getJoinGraceSeconds() * 1_000_000_000L));
            trustManager.load(p.getUniqueId(), p.getName());
            trustManager.onJoin(p.getUniqueId());
        }

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);

        updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(new UpdateListener(this), this);

        PluginCommand command = getCommand("zerosentinel");
        if (command != null) {
            ZeroSentinelCommand executor = new ZeroSentinelCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // 每秒：結束到期的錄製、關閉超過合併視窗的事件（兩者成本都很低）
        tickTask = getServer().getScheduler().runTaskTimer(this, () -> {
            recordingManager.tick();
            eventManager.expireOpen();
            clientReportCheck.tick();
        }, 20L, 20L);
        // 每分鐘：在線加分、清除過期的「最近踢出」紀錄
        minuteTask = getServer().getScheduler().runTaskTimer(this, () -> {
            trustManager.tickGain();
            punishmentManager.prune(configManager.getClientReportRepeatKickMinutes());
        }, 20L * 60, 20L * 60);
        // 啟動 1 分鐘後清理一次資料庫過期資料，之後每天一次（實際刪除在資料庫寫入執行緒）
        cleanupTask = getServer().getScheduler().runTaskTimer(this, this::cleanupDatabase, 20L * 60, DAY_TICKS);

        if (configManager.isUpdateCheckOnStartup()) {
            updateChecker.checkOnStartup();
        }
        getLogger().info("ZeroSentinel v" + getPluginMeta().getVersion() + " 已啟用。");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (minuteTask != null) {
            minuteTask.cancel();
        }
        if (checkListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(checkListener);
        }
        if (lagMonitor != null) {
            lagMonitor.stop();
        }
        if (guiManager != null) {
            guiManager.closeAll();
        }
        if (updateChecker != null) {
            updateChecker.shutdown();
        }
        if (recordingManager != null) {
            recordingManager.shutdown(10);
        }
        if (database != null) {
            database.close(10);
        }
        if (eventManager != null) {
            eventManager.clear();
        }
        if (trustManager != null) {
            trustManager.clear();
        }
        if (playerDataManager != null) {
            playerDataManager.clear();
        }
        if (punishmentManager != null) {
            punishmentManager.clear();
        }
    }

    /** 重新讀取 config.yml 與 messages.yml（自動補齊缺少的項目） */
    public void reload() {
        boolean wasEnabled = configManager.isRecordEnabled();
        int oldBuffer = configManager.getBufferBytes();
        configManager.load();
        messageManager.load();
        FloodgateHook.init(getLogger());
        eventManager.setCacheSize(configManager.getDbCacheSize());
        if (wasEnabled != configManager.isRecordEnabled() || oldBuffer != configManager.getBufferBytes()) {
            recordingManager.reload(getServer().getOnlinePlayers());
        }
    }

    private void cleanupDatabase() {
        int days = configManager.getDbRetentionDays();
        if (days <= 0 || database == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        database.cleanup(cutoff, deleted -> {
            if (deleted > 0) {
                getLogger().info("已清理 " + deleted + " 筆過期的事件與錯誤碼。");
            }
        });
    }

    /** I/O 執行緒：錄製結束 */
    private void onRecordingFinished(RecordingSession.Result result) {
        if (!result.success()) {
            return;
        }
        long eventId = result.header().eventId();
        if (eventId != 0 && eventManager != null) {
            eventManager.setRecording(eventId, recordingManager.storage().relative(result.file()));
        }
        getLogger().info("錄製完成：" + recordingManager.storage().relative(result.file())
                + "（" + result.entries() + " 筆封包，結束原因 " + result.reason() + "）");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public EventManager getEventManager() {
        return eventManager;
    }

    public RecordingManager getRecordingManager() {
        return recordingManager;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public TrustManager getTrustManager() {
        return trustManager;
    }

    public clre20.zeroSentinel.punish.VerdictService getVerdictService() {
        return verdictService;
    }

    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public ClientReportCheck getClientReportCheck() {
        return clientReportCheck;
    }

    public LagMonitor getLagMonitor() {
        return lagMonitor;
    }
}
