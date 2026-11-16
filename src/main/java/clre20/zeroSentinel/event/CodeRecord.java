package clre20.zeroSentinel.event;

import java.util.UUID;

/**
 * 錯誤碼與事件的對應。
 *
 * @param code 內部格式（9 個字元）
 */
public record CodeRecord(String code, long eventId, UUID uuid, long created, MessageType type) {

    public String display() {
        return ErrorCode.display(code);
    }
}
