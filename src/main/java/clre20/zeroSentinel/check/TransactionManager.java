package clre20.zeroSentinel.check;

import clre20.zeroSentinel.ZeroSentinel;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定時送出 PING（transaction），用來測量真實延遲並確認客戶端已處理伺服器送出的狀態變更。
 * 只有一條排程執行緒，每 250 毫秒檢查一次哪些玩家到了該送的時間，不會隨玩家人數增加執行緒。
 */
public final class TransactionManager {

    private static final long CHECK_EVERY_MS = 250;

    private final ZeroSentinel plugin;
    private final PlayerDataManager players;
    private ScheduledExecutorService exec;

    public TransactionManager(ZeroSentinel plugin, PlayerDataManager players) {
        this.plugin = plugin;
        this.players = players;
    }

    public void start() {
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ZeroSentinel-Transaction");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::tick, CHECK_EVERY_MS, CHECK_EVERY_MS, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
    }

    private void tick() {
        try {
            long interval = plugin.getConfigManager().getTransactionIntervalMs() * 1_000_000L;
            long now = System.nanoTime();
            for (PlayerData d : players.all()) {
                if (now - d.transaction().lastSendNanos() >= interval) {
                    send(d);
                }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("送出 transaction 時發生錯誤：" + e.getMessage());
        }
    }

    /** 送出一個 PING（任何執行緒都可呼叫；連線不在遊戲階段時略過） */
    public void send(PlayerData d) {
        if (!(d.peUser() instanceof User user)) {
            return;
        }
        if (user.getEncoderState() != ConnectionState.PLAY) {
            return;
        }
        int id = d.transaction().next(System.nanoTime());
        try {
            user.sendPacket(new WrapperPlayServerPing(id));
        } catch (RuntimeException ignored) {
            // 連線正在關閉
        }
    }
}
