package clre20.zeroSentinel.lag;

import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * 整個伺服器（JVM）停頓偵測。
 * <p>
 * 用一條獨立的看門狗執行緒每 100 毫秒睡一次，量測「實際睡了多久」。GC 全面暫停或主機 CPU 被搶走時，
 * 所有執行緒（包含 netty）都會停住，看門狗醒來時就會發現睡太久。停頓期間與恢復後的寬限時間內，
 * 檢查不應累積違規（玩家的封包會在恢復後一次湧入）。
 * </p>
 * 只有一條執行緒，用 sleep 等待，不會忙碌輪詢。
 */
public final class LagMonitor {

    private static final long SLEEP_MS = 100;

    private final Logger logger;
    private final LongSupplier stallThresholdMs;
    private final LongSupplier graceMs;
    private volatile long lastStallEndNanos = Long.MIN_VALUE / 2;
    private volatile long lastStallMs;
    private volatile int stallCount;
    private volatile boolean running;
    private Thread thread;

    public LagMonitor(Logger logger, LongSupplier stallThresholdMs, LongSupplier graceMs) {
        this.logger = logger;
        this.stallThresholdMs = stallThresholdMs;
        this.graceMs = graceMs;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "ZeroSentinel-Watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void loop() {
        long last = System.nanoTime();
        while (running) {
            try {
                Thread.sleep(SLEEP_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            long overMs = (now - last) / 1_000_000L - SLEEP_MS;
            last = now;
            if (overMs >= stallThresholdMs.getAsLong()) {
                recordStall(now, overMs);
            }
        }
    }

    /** 測試用：直接記錄一次停頓 */
    void recordStall(long endNanos, long durationMs) {
        lastStallEndNanos = endNanos;
        lastStallMs = durationMs;
        stallCount++;
        logger.warning("偵測到伺服器整體停頓約 " + durationMs + " 毫秒（所有執行緒同時暫停，常見原因為 GC 或主機 CPU 不足）。"
                + "停頓後 " + graceMs.getAsLong() / 1000 + " 秒內不累積違規。");
    }

    /** 現在是否在停頓後的寬限時間內 */
    public boolean inGrace(long nowNanos) {
        return nowNanos - lastStallEndNanos < graceMs.getAsLong() * 1_000_000L;
    }

    public int stallCount() {
        return stallCount;
    }

    public long lastStallMs() {
        return lastStallMs;
    }
}
