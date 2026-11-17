package clre20.zeroSentinel.event;

import java.util.Locale;

/** 錯誤碼是附在哪一種訊息上發給玩家的 */
public enum MessageType {
    WARN,
    RESTRICT,
    KICK,
    DENY_JOIN,
    TEMPBAN;

    public static MessageType parse(String s) {
        if (s == null) {
            return WARN;
        }
        try {
            return valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return WARN;
        }
    }
}
