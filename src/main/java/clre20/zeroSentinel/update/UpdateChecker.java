package clre20.zeroSentinel.update;

import clre20.zeroSentinel.ZeroSentinel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 向 GitHub Releases 檢查更新。
 * <ul>
 *     <li>網路請求一律在 Bukkit 非同步執行緒池執行，不自行開執行緒。</li>
 *     <li>結果快取 10 分鐘；同時多個請求只會發出一次連線。</li>
 *     <li>沒有定時輪詢，只在啟動、管理員登入、執行 version 指令時才檢查。</li>
 * </ul>
 */
public final class UpdateChecker {

    public static final String REPO = "clre20/ZeroSentinel";
    public static final String RELEASES_URL = "https://github.com/" + REPO + "/releases";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CACHE_MS = 10L * 60L * 1000L;
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final int TIMEOUT_MS = 5000;

    public enum Status {
        /** 有新版本 */
        OUTDATED,
        /** 已是最新 */
        UP_TO_DATE,
        /** 目前版本比 GitHub 新（開發版） */
        DEV_BUILD,
        /** GitHub 尚未發布任何 Release */
        NO_RELEASE,
        /** 檢查失敗 */
        FAILED
    }

    public record Result(Status status, String current, String latest) {
    }

    private final ZeroSentinel plugin;
    private final String currentVersion;
    private final Object lock = new Object();
    private final List<Consumer<Result>> waiting = new ArrayList<>();

    private Result cached;
    private long cachedAt;
    private boolean inFlight;
    private volatile boolean shutdown;

    public UpdateChecker(ZeroSentinel plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getPluginMeta().getVersion();
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    /** 啟動時非同步檢查並輸出到後台 */
    public void checkOnStartup() {
        check(result -> {
            switch (result.status()) {
                case OUTDATED -> plugin.getLogger().warning("發現新版本 v" + result.latest()
                        + "（目前 v" + result.current() + "）：" + RELEASES_URL);
                case UP_TO_DATE -> plugin.getLogger().info("目前已是最新版本 v" + result.current() + "。");
                case DEV_BUILD -> plugin.getLogger().info("目前版本 v" + result.current()
                        + " 比 GitHub 最新版 v" + result.latest() + " 新（開發版）。");
                case NO_RELEASE -> plugin.getLogger().info("GitHub 尚未發布任何 Release。");
                case FAILED -> plugin.getLogger().warning("檢查更新失敗，請稍後再試。");
            }
        });
    }

    /**
     * 取得更新資訊。callback 一定在主執行緒執行。
     */
    public void check(Consumer<Result> callback) {
        boolean startRequest;
        Result fresh = null;
        synchronized (lock) {
            if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
                fresh = cached;
                startRequest = false;
            } else {
                waiting.add(callback);
                startRequest = !inFlight;
                inFlight = true;
            }
        }
        if (fresh != null) {
            callback.accept(fresh);
            return;
        }
        if (startRequest) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::fetch);
        }
    }

    private void fetch() {
        Result result = request();
        List<Consumer<Result>> callbacks;
        synchronized (lock) {
            cached = result;
            cachedAt = System.currentTimeMillis();
            inFlight = false;
            callbacks = new ArrayList<>(waiting);
            waiting.clear();
        }
        if (shutdown || !plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Consumer<Result> cb : callbacks) {
                try {
                    cb.accept(result);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "處理更新檢查結果時發生錯誤", e);
                }
            }
        });
    }

    private Result request() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "ZeroSentinel/" + currentVersion);
            int code = conn.getResponseCode();
            if (code == 404) {
                return new Result(Status.NO_RELEASE, currentVersion, null);
            }
            if (code != 200) {
                plugin.getLogger().fine("GitHub API 回應 " + code);
                return new Result(Status.FAILED, currentVersion, null);
            }
            String body;
            try (InputStream in = conn.getInputStream()) {
                body = readLimited(in);
            }
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return new Result(Status.FAILED, currentVersion, null);
            }
            JsonObject obj = root.getAsJsonObject();
            JsonElement tag = obj.get("tag_name");
            if (tag == null || !tag.isJsonPrimitive()) {
                return new Result(Status.FAILED, currentVersion, null);
            }
            String latest = stripV(tag.getAsString());
            int cmp = compare(stripV(currentVersion), latest);
            Status status = cmp < 0 ? Status.OUTDATED : cmp > 0 ? Status.DEV_BUILD : Status.UP_TO_DATE;
            return new Result(status, currentVersion, latest);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().fine("檢查更新失敗：" + e.getMessage());
            return new Result(Status.FAILED, currentVersion, null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_BODY_BYTES) {
                throw new IOException("回應內容過大");
            }
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static String stripV(String version) {
        if (version == null) {
            return "";
        }
        String v = version.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        return v;
    }

    /** 比較 x.y.z，非數字部分（例如 -SNAPSHOT）忽略 */
    static int compare(String a, String b) {
        int[] pa = parse(a);
        int[] pb = parse(b);
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) {
                return Integer.compare(pa[i], pb[i]);
            }
        }
        return 0;
    }

    private static int[] parse(String v) {
        int[] out = new int[3];
        String[] parts = v.split("[.\\-+]");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D", "").isEmpty() ? "0" : parts[i].replaceAll("\\D", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    public void shutdown() {
        shutdown = true;
        synchronized (lock) {
            waiting.clear();
        }
    }
}
