package clre20.zeroSentinel.record;

import clre20.zeroSentinel.ZeroSentinel;
import clre20.zeroSentinel.compatibility.FloodgateHook;
import clre20.zeroSentinel.config.ConfigManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 封包錄製總管。
 * <ul>
 *     <li>玩家送給伺服器的封包：全部記錄（LOWEST 優先度，取得其他插件修改前的原始內容）。</li>
 *     <li>伺服器送給玩家的封包：只記錄與判定有關的類型（MONITOR 優先度）；區塊資料不記錄。</li>
 *     <li>硬碟寫入、當機修復、過期清理全部在單一 I/O 執行緒執行。</li>
 * </ul>
 */
public final class RecordingManager {

    public enum StartResult {
        STARTED,
        ALREADY_RECORDING,
        DISABLED,
        NOT_TRACKED
    }

    /** 伺服器送出的封包中，需要記錄的類型（判定時會用到） */
    private static final Set<PacketType.Play.Server> OUTBOUND = EnumSet.of(
            PacketType.Play.Server.PLAYER_POSITION_AND_LOOK,
            PacketType.Play.Server.PLAYER_ROTATION,
            PacketType.Play.Server.ENTITY_VELOCITY,
            PacketType.Play.Server.EXPLOSION,
            PacketType.Play.Server.BLOCK_CHANGE,
            PacketType.Play.Server.MULTI_BLOCK_CHANGE,
            PacketType.Play.Server.ACKNOWLEDGE_BLOCK_CHANGES,
            PacketType.Play.Server.PING,
            PacketType.Play.Server.KEEP_ALIVE,
            PacketType.Play.Server.ENTITY_EFFECT,
            PacketType.Play.Server.REMOVE_ENTITY_EFFECT,
            PacketType.Play.Server.UPDATE_ATTRIBUTES,
            PacketType.Play.Server.PLAYER_ABILITIES,
            PacketType.Play.Server.CHANGE_GAME_STATE,
            PacketType.Play.Server.SET_PASSENGERS,
            PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.DESTROY_ENTITIES,
            PacketType.Play.Server.ENTITY_METADATA,
            PacketType.Play.Server.ENTITY_RELATIVE_MOVE,
            PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION,
            PacketType.Play.Server.ENTITY_TELEPORT,
            PacketType.Play.Server.ENTITY_POSITION_SYNC,
            PacketType.Play.Server.MOVE_MINECART,
            PacketType.Play.Server.RESPAWN,
            PacketType.Play.Server.UNLOAD_CHUNK,
            PacketType.Play.Server.UPDATE_VIEW_POSITION,
            PacketType.Play.Server.WORLD_BORDER,
            PacketType.Play.Server.INITIALIZE_WORLD_BORDER,
            PacketType.Play.Server.WORLD_BORDER_CENTER,
            PacketType.Play.Server.WORLD_BORDER_SIZE,
            PacketType.Play.Server.WORLD_BORDER_LERP_SIZE
    );

    /** 被追蹤的玩家 */
    private record Tracked(PlayerRecorder recorder, int clientProtocol, boolean bedrock) {
    }

    /** 由 netty ByteBuf 複製資料（每條執行緒一個，重用） */
    private static final class BufSource implements PacketRing.ByteSource {
        private Object buf;
        private int base;

        @Override
        public void copyTo(int srcOffset, byte[] dst, int dstOffset, int length) {
            ByteBufHelper.readerIndex(buf, base + srcOffset);
            ByteBufHelper.readBytes(buf, dst, dstOffset, length);
        }
    }

    private static final ThreadLocal<BufSource> SOURCES = ThreadLocal.withInitial(BufSource::new);

    private final ZeroSentinel plugin;
    private final ConfigManager config;
    private final RecordingStorage storage;
    private final ScheduledExecutorService io;
    private final Map<UUID, Tracked> tracked = new ConcurrentHashMap<>();
    private final Consumer<RecordingSession.Result> onFinished;
    private PacketListenerCommon receiveListener;
    private PacketListenerCommon sendListener;
    private volatile String serverVersion = "";
    private volatile int serverProtocol;
    /** 錄製檔總大小（只在 I/O 執行緒更新）；每次錄製結束就檢查上限，不只靠每天的清理 */
    private long knownTotalBytes;

    public RecordingManager(ZeroSentinel plugin, ConfigManager config, Path root,
                            Consumer<RecordingSession.Result> onFinished) {
        this.plugin = plugin;
        this.config = config;
        this.storage = new RecordingStorage(root, plugin.getLogger());
        this.onFinished = onFinished;
        this.io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ZeroSentinel-IO");
            t.setDaemon(true);
            return t;
        });
    }

    public RecordingStorage storage() {
        return storage;
    }

    public void start() {
        serverVersion = plugin.getServer().getMinecraftVersion();
        serverProtocol = PacketEvents.getAPI().getServerManager().getVersion().getProtocolVersion();

        io.execute(() -> {
            int recovered = storage.recoverPartFiles();
            if (recovered > 0) {
                plugin.getLogger().info("已修復 " + recovered + " 個未完成的錄製檔。");
            }
            knownTotalBytes = storage.totalSize();
        });
        // 啟動後 1 分鐘第一次清理，之後每天一次
        io.scheduleWithFixedDelay(this::cleanupSafe, 1, 24 * 60, TimeUnit.MINUTES);

        receiveListener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                handle(event.getPacketType(), event.getUser(), event.getPacketId(), event.getByteBuf(),
                        RecordEntry.CLIENT_TO_SERVER);
            }
        }, PacketListenerPriority.LOWEST);
        sendListener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                PacketTypeCommon type = event.getPacketType();
                if (!(type instanceof PacketType.Play.Server server) || !OUTBOUND.contains(server)) {
                    return;
                }
                handle(type, event.getUser(), event.getPacketId(), event.getByteBuf(),
                        RecordEntry.SERVER_TO_CLIENT);
            }
        }, PacketListenerPriority.MONITOR);

        for (Player p : plugin.getServer().getOnlinePlayers()) {
            track(p);
        }
    }

    /** netty 執行緒：所有判斷都盡量便宜，非追蹤玩家立即返回 */
    private void handle(PacketTypeCommon type, User user, int packetId, Object buf, int direction) {
        if (direction == RecordEntry.CLIENT_TO_SERVER && !(type instanceof PacketType.Play.Client)) {
            return;
        }
        if (user == null || buf == null) {
            return;
        }
        UUID uuid = user.getUUID();
        if (uuid == null) {
            return;
        }
        Tracked t = tracked.get(uuid);
        if (t == null) {
            return;
        }
        int readerIndex = ByteBufHelper.readerIndex(buf);
        int size = ByteBufHelper.readableBytes(buf);
        boolean truncated = size > config.getMaxPacketBytes();
        BufSource source = SOURCES.get();
        source.buf = buf;
        source.base = readerIndex;
        try {
            t.recorder().record(System.nanoTime(), direction, packetId, size, truncated, source);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "記錄封包失敗", e);
        } finally {
            ByteBufHelper.readerIndex(buf, readerIndex);
            source.buf = null;
        }
    }

    /** 主執行緒：玩家進服時開始追蹤 */
    public void track(Player player) {
        if (!config.isRecordEnabled()) {
            return;
        }
        int clientProtocol = serverProtocol;
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user != null && user.getClientVersion() != null) {
                clientProtocol = user.getClientVersion().getProtocolVersion();
            }
        } catch (RuntimeException ignored) {
            // 取不到時以伺服器協議版本記錄
        }
        boolean bedrock = FloodgateHook.isBedrock(player.getUniqueId());
        tracked.put(player.getUniqueId(), new Tracked(
                new PlayerRecorder(player.getUniqueId(), player.getName(), config.getBufferBytes()),
                clientProtocol, bedrock));
    }

    /** 主執行緒：玩家離線時結束錄製並釋放緩衝區 */
    public void untrack(UUID uuid) {
        Tracked t = tracked.remove(uuid);
        if (t != null) {
            t.recorder().stop(RecordingSession.EndReason.DISCONNECT);
        }
    }

    /**
     * 手動錄製：包含最近 buffer-seconds 秒，並持續 seconds 秒。
     */
    public StartResult startManual(UUID uuid, int seconds) {
        return start(uuid, seconds, 0L, "manual");
    }

    /** 事件觸發的錄製：包含最近 buffer-seconds 秒，並持續 after-flag-seconds 秒 */
    public StartResult startForEvent(UUID uuid, long eventId) {
        if (!config.isRecordOnFlag()) {
            return StartResult.DISABLED;
        }
        return start(uuid, config.getAfterFlagSeconds(), eventId, "event");
    }

    private StartResult start(UUID uuid, int seconds, long eventId, String reason) {
        if (!config.isRecordEnabled()) {
            return StartResult.DISABLED;
        }
        Tracked t = tracked.get(uuid);
        if (t == null) {
            return StartResult.NOT_TRACKED;
        }
        long now = System.nanoTime();
        long minNanos = now - config.getBufferSeconds() * 1_000_000_000L;
        long endNanos = now + Math.max(0, seconds) * 1_000_000_000L;
        String tag = eventId != 0 ? clre20.zeroSentinel.event.EventId.format(eventId) : reason;
        Path file = storage.newFile(t.recorder().name(), uuid, tag);
        RecordingSession session = t.recorder().start(minNanos, base -> {
            long startMillis = System.currentTimeMillis() - (now - base) / 1_000_000L;
            RecordingHeader header = new RecordingHeader(RecordingHeader.FORMAT_VERSION,
                    plugin.getPluginMeta().getVersion(), serverVersion, serverProtocol, t.clientProtocol(),
                    uuid, t.recorder().name(), eventId, reason, startMillis, t.bedrock(), new byte[0]);
            return new RecordingSession(io, plugin.getLogger(), file, header, base, endNanos,
                    config.getMaxSessionBytes(), this::finished);
        });
        return session == null ? StartResult.ALREADY_RECORDING : StartResult.STARTED;
    }

    /** I/O 執行緒 */
    private void finished(RecordingSession.Result result) {
        if (result.success()) {
            try {
                knownTotalBytes += java.nio.file.Files.size(result.file());
            } catch (java.io.IOException ignored) {
                // 檔案大小取不到時，下次清理會重新計算
            }
            long max = config.getRecordingsMaxTotalBytes();
            if (max > 0 && knownTotalBytes > max) {
                cleanupSafe();
            }
        }
        if (onFinished != null) {
            onFinished.accept(result);
        }
    }

    /** @return 是否有錄製被停止 */
    public boolean stop(UUID uuid) {
        Tracked t = tracked.get(uuid);
        return t != null && t.recorder().stop(RecordingSession.EndReason.STOPPED) != null;
    }

    public boolean isRecording(UUID uuid) {
        Tracked t = tracked.get(uuid);
        return t != null && t.recorder().isRecording();
    }

    /** 剩餘錄製秒數；沒有在錄製時回傳 -1 */
    public long remainingSeconds(UUID uuid) {
        Tracked t = tracked.get(uuid);
        if (t == null) {
            return -1;
        }
        RecordingSession s = t.recorder().current();
        if (s == null || s.isFinished()) {
            return -1;
        }
        return Math.max(0, (s.endNanos() - System.nanoTime()) / 1_000_000_000L);
    }

    public int recordingCount() {
        int n = 0;
        for (Tracked t : tracked.values()) {
            if (t.recorder().isRecording()) {
                n++;
            }
        }
        return n;
    }

    public boolean isBedrock(UUID uuid) {
        Tracked t = tracked.get(uuid);
        return t != null && t.bedrock();
    }

    public int clientProtocol(UUID uuid) {
        Tracked t = tracked.get(uuid);
        return t == null ? -1 : t.clientProtocol();
    }

    /** 主執行緒每秒呼叫：結束到期的錄製 */
    public void tick() {
        long now = System.nanoTime();
        for (Tracked t : tracked.values()) {
            t.recorder().checkExpiry(now);
        }
    }

    /** 重載設定後：錄製開關或緩衝區大小變更時重建追蹤 */
    public void reload(Iterable<? extends Player> online) {
        for (UUID uuid : tracked.keySet()) {
            untrack(uuid);
        }
        for (Player p : online) {
            track(p);
        }
    }

    /** 在 I/O 執行緒執行，結果回到 callback（I/O 執行緒） */
    public void runIo(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException ignored) {
            // 已關閉
        }
    }

    private void cleanupSafe() {
        try {
            int deleted = storage.cleanup(config.getRecordingsRetentionDays(), config.getRecordingsMaxTotalBytes());
            knownTotalBytes = storage.totalSize();
            if (deleted > 0) {
                plugin.getLogger().info("已清理 " + deleted + " 個過期或超出容量的錄製檔。");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "清理錄製檔失敗", e);
        }
    }

    /** 結束所有錄製並等待寫入完成（最多 timeoutSeconds 秒） */
    public void shutdown(int timeoutSeconds) {
        if (receiveListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(receiveListener);
        }
        if (sendListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(sendListener);
        }
        for (Tracked t : tracked.values()) {
            t.recorder().stop(RecordingSession.EndReason.SHUTDOWN);
        }
        tracked.clear();
        io.shutdown();
        try {
            if (!io.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("錄製檔未能在時間內全部寫完，下次啟動會自動修復。");
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            io.shutdownNow();
        }
    }
}
