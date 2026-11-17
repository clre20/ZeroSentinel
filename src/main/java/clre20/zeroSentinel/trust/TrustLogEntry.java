package clre20.zeroSentinel.trust;

import java.util.UUID;

/** 管理員修改信用點數的紀錄 */
public record TrustLogEntry(UUID uuid, String actor, int delta, int score, String reason, long time) {
}
