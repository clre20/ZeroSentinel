package clre20.zeroSentinel.event;

import java.util.UUID;

/**
 * 事件（不可變；更新時以新物件取代，快取與各執行緒之間可以安全共用）。
 *
 * @param violations 這個事件累積的違規次數
 * @param recording  錄製檔相對路徑；沒有錄製時為 null
 * @param trustDeducted 這個事件扣掉的信用點數（判定為合法時退還，退還後歸零）
 */
public record EventRecord(
        long id,
        UUID uuid,
        String playerName,
        String category,
        long created,
        long lastSeen,
        int violations,
        Verdict verdict,
        String verdictBy,
        String verdictNote,
        long verdictAt,
        String recording,
        int trustDeducted
) {

    public EventRecord withSeen(long time) {
        return new EventRecord(id, uuid, playerName, category, created, time, violations + 1,
                verdict, verdictBy, verdictNote, verdictAt, recording, trustDeducted);
    }

    public EventRecord withVerdict(Verdict v, String by, String note, long at) {
        return new EventRecord(id, uuid, playerName, category, created, lastSeen, violations,
                v, by, note, at, recording, trustDeducted);
    }

    public EventRecord withRecording(String path) {
        return new EventRecord(id, uuid, playerName, category, created, lastSeen, violations,
                verdict, verdictBy, verdictNote, verdictAt, path, trustDeducted);
    }

    public EventRecord withTrustDeducted(int amount) {
        return new EventRecord(id, uuid, playerName, category, created, lastSeen, violations,
                verdict, verdictBy, verdictNote, verdictAt, recording, Math.max(0, amount));
    }
}
