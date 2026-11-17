package clre20.zeroSentinel.trust;

import java.util.UUID;

/**
 * 玩家的信用點數（不可變；更新時以新物件取代）。
 *
 * @param dailyGain 今天已靠在線時間增加的分數（用來限制每日上限）
 * @param gainDay   dailyGain 對應的日期（epoch day）
 */
public record TrustRecord(UUID uuid, String name, int score, int dailyGain, long gainDay, long updated) {

    public TrustRecord withScore(int newScore, long now) {
        return new TrustRecord(uuid, name, newScore, dailyGain, gainDay, now);
    }

    public TrustRecord withName(String newName) {
        return new TrustRecord(uuid, newName, score, dailyGain, gainDay, updated);
    }

    public TrustRecord withGain(int newScore, int newDailyGain, long day, long now) {
        return new TrustRecord(uuid, name, newScore, newDailyGain, day, now);
    }
}
