package clre20.zeroSentinel.record;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * 錄製檔（.zsr.gz）檔頭。封包內容一律是「伺服器協議版本」的格式。
 *
 * @param snapshot 世界快照區段；第 0 階段為空，第 1、2 階段做出世界補償後填入
 */
public record RecordingHeader(
        int formatVersion,
        String pluginVersion,
        String serverVersion,
        int serverProtocol,
        int clientProtocol,
        UUID uuid,
        String playerName,
        long eventId,
        String reason,
        long startEpochMillis,
        boolean bedrock,
        byte[] snapshot
) {

    public static final byte[] MAGIC = {'Z', 'S', 'R', '1'};
    public static final int FORMAT_VERSION = 1;
    /** 讀取時字串與快照的上限，避免損壞或惡意檔案造成大量配置記憶體 */
    static final int MAX_STRING_BYTES = 1024;
    static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;

    void write(DataOutputStream out) throws IOException {
        out.write(MAGIC);
        out.writeShort(formatVersion);
        writeString(out, pluginVersion);
        writeString(out, serverVersion);
        out.writeInt(serverProtocol);
        out.writeInt(clientProtocol);
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
        writeString(out, playerName);
        out.writeLong(eventId);
        writeString(out, reason);
        out.writeLong(startEpochMillis);
        out.writeBoolean(bedrock);
        byte[] snap = snapshot == null ? new byte[0] : snapshot;
        out.writeInt(snap.length);
        out.write(snap);
    }

    static RecordingHeader read(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        for (int i = 0; i < 4; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("不是 ZeroSentinel 錄製檔");
            }
        }
        int version = in.readUnsignedShort();
        if (version != FORMAT_VERSION) {
            throw new IOException("不支援的錄製檔格式版本：" + version);
        }
        String pluginVersion = readString(in);
        String serverVersion = readString(in);
        int serverProtocol = in.readInt();
        int clientProtocol = in.readInt();
        UUID uuid = new UUID(in.readLong(), in.readLong());
        String name = readString(in);
        long eventId = in.readLong();
        String reason = readString(in);
        long start = in.readLong();
        boolean bedrock = in.readBoolean();
        int snapLen = in.readInt();
        if (snapLen < 0 || snapLen > MAX_SNAPSHOT_BYTES) {
            throw new IOException("快照長度不合法：" + snapLen);
        }
        byte[] snap = new byte[snapLen];
        in.readFully(snap);
        return new RecordingHeader(version, pluginVersion, serverVersion, serverProtocol, clientProtocol,
                uuid, name, eventId, reason, start, bedrock, snap);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = Math.min(b.length, MAX_STRING_BYTES);
        out.writeShort(len);
        out.write(b, 0, len);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len > MAX_STRING_BYTES) {
            throw new IOException("字串長度不合法：" + len);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }
}
