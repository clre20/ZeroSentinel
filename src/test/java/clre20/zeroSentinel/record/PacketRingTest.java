package clre20.zeroSentinel.record;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketRingTest {

    private static PacketRing.ByteSource source(byte[] data) {
        return (srcOffset, dst, dstOffset, length) -> System.arraycopy(data, srcOffset, dst, dstOffset, length);
    }

    private static byte[] payload(int seed, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = (byte) (seed * 31 + i);
        }
        return b;
    }

    @Test
    void keepsNewestEntriesInOrderAcrossWrap() {
        PacketRing ring = new PacketRing(4096);
        for (int i = 0; i < 1000; i++) {
            byte[] p = payload(i, 1 + (i % 97));
            ring.append(i, i & 1, i % 128, p.length, false, source(p));
            assertTrue(ring.used() <= ring.capacity());
        }
        List<Long> times = new ArrayList<>();
        ring.visit(0, (nanos, dir, trunc, id, size, data) -> {
            int i = (int) nanos;
            assertEquals(i & 1, dir);
            assertEquals(i % 128, id);
            assertFalse(trunc);
            assertArrayEquals(payload(i, 1 + (i % 97)), data);
            times.add(nanos);
        });
        assertFalse(times.isEmpty());
        assertEquals(999L, times.get(times.size() - 1));
        for (int i = 1; i < times.size(); i++) {
            assertEquals(times.get(i - 1) + 1, times.get(i));
        }
    }

    @Test
    void visitRespectsMinTime() {
        PacketRing ring = new PacketRing(64 * 1024);
        for (int i = 0; i < 100; i++) {
            byte[] p = payload(i, 10);
            ring.append(i * 1000L, 0, 1, p.length, false, source(p));
        }
        int[] count = {0};
        ring.visit(50_000L, (n, d, t, id, s, data) -> {
            assertTrue(n >= 50_000L);
            count[0]++;
        });
        assertEquals(50, count[0]);
    }

    @Test
    void oversizedPayloadIsTruncated() {
        PacketRing ring = new PacketRing(1024);
        byte[] big = payload(1, 900);
        ring.append(1, 0, 5, big.length, false, source(big));
        ring.visit(0, (n, d, t, id, s, data) -> {
            assertTrue(t);
            assertEquals(900, s);
            assertEquals(0, data.length);
        });
        ring.append(2, 1, 6, 10, true, null);
        assertEquals(2, ring.count());
    }
}
