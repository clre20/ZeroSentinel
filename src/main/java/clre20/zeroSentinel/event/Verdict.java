package clre20.zeroSentinel.event;

import java.util.Locale;

/** 管理員對事件的判定 */
public enum Verdict {
    NONE,
    LEGIT,
    CHEAT;

    /** @return 對應的判定；無法辨識時回傳 null */
    public static Verdict parseInput(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "legit" -> LEGIT;
            case "cheat" -> CHEAT;
            default -> null;
        };
    }

    public static Verdict fromDb(String s) {
        if (s == null) {
            return NONE;
        }
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
