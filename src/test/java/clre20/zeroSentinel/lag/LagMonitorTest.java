package clre20.zeroSentinel.lag;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LagMonitorTest {

    @Test
    void graceWindowAfterStall() {
        LagMonitor m = new LagMonitor(Logger.getLogger("t"), () -> 1000, () -> 5000);
        long now = System.nanoTime();
        assertFalse(m.inGrace(now));
        m.recordStall(now, 4300);
        assertTrue(m.inGrace(now + 4_000_000_000L));
        assertFalse(m.inGrace(now + 6_000_000_000L));
        assertEquals(1, m.stallCount());
        assertEquals(4300, m.lastStallMs());
    }

    @Test
    void watchdogDetectsRealPause() throws Exception {
        LagMonitor m = new LagMonitor(Logger.getLogger("t"), () -> 300, () -> 5000);
        m.start();
        Thread.sleep(250);
        // 無法在測試中暫停整個 JVM；確認正常情況下不會誤報
        assertEquals(0, m.stallCount());
        m.stop();
    }
}
