package clre20.zeroSentinel.event;

import clre20.zeroSentinel.config.MessageManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 事件相關文字的格式化（指令與箱子 UI 共用） */
public final class EventFormat {

    private EventFormat() {
    }

    public static String time(DateTimeFormatter format, long epochMillis) {
        if (epochMillis <= 0) {
            return "-";
        }
        return format.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    public static String verdict(MessageManager messages, Verdict verdict) {
        return switch (verdict == null ? Verdict.NONE : verdict) {
            case NONE -> messages.raw("verdict.none");
            case LEGIT -> messages.raw("verdict.legit");
            case CHEAT -> messages.raw("verdict.cheat");
        };
    }

    /** 錯誤碼清單（顯示格式），最多 max 組，超過時加上「…等 N 組」 */
    public static String codes(MessageManager messages, List<CodeRecord> codes, int max) {
        if (codes.isEmpty()) {
            return messages.raw("lookup.no-codes");
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(max, codes.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(codes.get(i).display());
        }
        if (codes.size() > n) {
            sb.append(messages.raw("lookup.more-codes", "count", String.valueOf(codes.size())));
        }
        return sb.toString();
    }
}
