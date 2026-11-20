package clre20.zeroSentinel.check;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransactionStateTest {

    private static final long MS = 1_000_000L;

    @Test
    void idsAreNegativeAndRoundTrip() {
        TransactionState t = new TransactionState();
        for (int i = 0; i < 100_000; i++) {
            int id = t.next(i * MS);
            assertTrue(TransactionState.isOurs(id), "id " + id);
            assertEquals(i, t.onPong(id, i * MS + 50 * MS));
        }
        assertEquals(50, t.pingMs());
        assertEquals(0, t.pending());
        assertFalse(TransactionState.isOurs(0));
        assertFalse(TransactionState.isOurs(5));
        assertFalse(TransactionState.isOurs(-30001));
    }

    @Test
    void unknownOrDuplicatePongIgnored() {
        TransactionState t = new TransactionState();
        int a = t.next(0);
        int b = t.next(10 * MS);
        assertEquals(-1, t.onPong(-12345, 20 * MS));
        assertEquals(1, t.onPong(b, 30 * MS));
        // 已經確認過更新的序號，舊的不再接受
        assertEquals(-1, t.onPong(a, 40 * MS));
        assertEquals(-1, t.onPong(b, 50 * MS));
        assertEquals(20, t.lastRttMs());
    }

    @Test
    void abilitiesConfirmedOnlyAfterNextPing() {
        TransactionState t = new TransactionState();
        t.setConfirmed(false, false);
        assertFalse(t.mayFly());
        int before = t.next(0);          // 權限變更前就送出的 PING
        t.serverAbilities(true, true);    // 伺服器給予飛行與創造
        assertTrue(t.mayFly());          // 尚未確認：新舊狀態都算合法
        assertTrue(t.mayCreative());
        int after = t.next(1 * MS);       // 權限變更後送出的 PING
        t.onPong(before, 2 * MS);         // 舊的 PING 回來：還沒確認
        t.serverAbilities(false, false);  // 又收回權限
        assertTrue(t.mayFly());          // 連續變更期間取寬鬆
        int last = t.next(3 * MS);
        t.onPong(after, 4 * MS);
        t.onPong(last, 5 * MS);           // 最後一次變更已確認
        assertFalse(t.mayFly());
        assertFalse(t.mayCreative());
    }
}
