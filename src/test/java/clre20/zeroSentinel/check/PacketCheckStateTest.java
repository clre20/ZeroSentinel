package clre20.zeroSentinel.check;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketCheckStateTest {

    @Test
    void staticRules() {
        assertFalse(PacketRule.invalidPosition(100.5, 64, -2000));
        assertFalse(PacketRule.invalidPosition(2.9999984E7, -64, -2.9999984E7));
        assertTrue(PacketRule.invalidPosition(Double.NaN, 64, 0));
        assertTrue(PacketRule.invalidPosition(0, Double.POSITIVE_INFINITY, 0));
        assertTrue(PacketRule.invalidPosition(3.1E7, 64, 0));
        assertTrue(PacketRule.invalidPosition(0, 2.1E7, 0));
        assertFalse(PacketRule.invalidRotation(123456.7f, 90f));
        assertFalse(PacketRule.invalidRotation(-720f, -90f));
        assertTrue(PacketRule.invalidRotation(0f, 90.01f));
        assertTrue(PacketRule.invalidRotation(0f, -95f));
        assertTrue(PacketRule.invalidRotation(Float.NaN, 0f));
        assertTrue(PacketRule.invalidSlot(-1));
        assertTrue(PacketRule.invalidSlot(9));
        assertFalse(PacketRule.invalidSlot(0));
        assertFalse(PacketRule.invalidSlot(8));
    }

    @Test
    void heldSlot() {
        PacketCheckState s = new PacketCheckState();
        assertNull(s.onHeldSlot(3));
        assertNull(s.onHeldSlot(4));
        assertEquals(PacketRule.DUPLICATE_SLOT, s.onHeldSlot(4));
        s.onServerHeldSlot();
        assertNull(s.onHeldSlot(4)); // 伺服器設定過手持格後，同一格不算重複
        assertEquals(PacketRule.INVALID_SLOT, s.onHeldSlot(42));
    }

    @Test
    void sprint() {
        PacketCheckState s = new PacketCheckState();
        assertNull(s.onSprint(false)); // 第一次：狀態未知
        assertNull(s.onSprint(true));
        assertEquals(PacketRule.DUPLICATE_SPRINT, s.onSprint(true));
        assertNull(s.onSprint(false));
        assertEquals(PacketRule.DUPLICATE_SPRINT, s.onSprint(false));
    }

    @Test
    void teleportConfirm() {
        PacketCheckState s = new PacketCheckState();
        assertNull(s.onTeleportConfirm(99)); // 還沒看過伺服器的傳送：不判定
        s.onTeleportSent(10);
        s.onTeleportSent(11);
        assertNull(s.onTeleportConfirm(10));
        assertNull(s.onTeleportConfirm(11));
        assertNull(s.onTeleportConfirm(5));  // 較舊、不在紀錄中：不判定
        assertEquals(PacketRule.INVALID_TELEPORT_CONFIRM, s.onTeleportConfirm(12));
        for (int i = 12; i < 100; i++) {
            s.onTeleportSent(i);
        }
        assertNull(s.onTeleportConfirm(99));
        assertNull(s.onTeleportConfirm(11)); // 已被擠出紀錄的舊 ID
    }

    @Test
    void reportThrottle() {
        PacketCheckState s = new PacketCheckState();
        long sec = 1_000_000_000L;
        assertEquals(1, s.report(PacketRule.DUPLICATE_SLOT, 0, 10 * sec)); // 第一次立即回報
        for (int i = 1; i <= 50; i++) {
            assertEquals(0, s.report(PacketRule.DUPLICATE_SLOT, i * sec / 10, 10 * sec));
        }
        assertEquals(51, s.report(PacketRule.DUPLICATE_SLOT, 11 * sec, 10 * sec)); // 累計回報
        assertEquals(1, s.report(PacketRule.INVALID_SLOT, 11 * sec, 10 * sec));     // 不同規則各自節流
    }
}
