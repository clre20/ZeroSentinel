package clre20.zeroSentinel.event;

import clre20.zeroSentinel.util.Crockford;

import java.security.SecureRandom;

/**
 * 內部事件 ID（只給管理員與開發使用，不會顯示給玩家）。
 * 64 位元：高 44 位為毫秒時間戳（自 2026-01-01 起算），低 20 位為隨機數，依時間排序。
 */
public final class EventId {

    private static final long EPOCH = 1767225600000L; // 2026-01-01T00:00:00Z
    private static final SecureRandom RANDOM = new SecureRandom();

    private EventId() {
    }

    public static long next() {
        long millis = Math.max(0, System.currentTimeMillis() - EPOCH);
        return (millis << 20) | (RANDOM.nextInt(1 << 20));
    }

    /** 事件建立時間（epoch 毫秒） */
    public static long timestamp(long id) {
        return (id >>> 20) + EPOCH;
    }

    /** 顯示格式：E 加 13 個 Crockford 字元 */
    public static String format(long id) {
        return "E" + Crockford.encodeLong(id);
    }

    /**
     * @return 事件 ID；格式不合法時回傳 null
     */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.length() == 14 && (t.charAt(0) == 'E' || t.charAt(0) == 'e')) {
            t = t.substring(1);
        }
        return Crockford.decodeLong(t);
    }
}
