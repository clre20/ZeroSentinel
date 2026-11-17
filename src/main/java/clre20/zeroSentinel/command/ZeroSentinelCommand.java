package clre20.zeroSentinel.command;

import clre20.zeroSentinel.ZeroSentinel;
import clre20.zeroSentinel.config.MessageManager;
import clre20.zeroSentinel.event.CodeRecord;
import clre20.zeroSentinel.event.ErrorCode;
import clre20.zeroSentinel.event.EventFormat;
import clre20.zeroSentinel.event.EventId;
import clre20.zeroSentinel.event.EventManager;
import clre20.zeroSentinel.event.EventRecord;
import clre20.zeroSentinel.event.MessageType;
import clre20.zeroSentinel.event.Verdict;
import clre20.zeroSentinel.record.RecordingManager;
import clre20.zeroSentinel.update.UpdateChecker;
import clre20.zeroSentinel.util.Sync;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * /zerosentinel（別名 /zs）。每個子指令各自檢查權限，所有玩家輸入都會驗證長度與格式。
 */
public final class ZeroSentinelCommand implements TabExecutor {

    private static final int MAX_NOTE_LENGTH = 100;
    /** /zs record all：所有線上玩家 */
    private static final String ALL = "all";
    private static final List<String> SECONDS_SUGGESTIONS = List.of("30", "60", "120", "300");

    private final ZeroSentinel plugin;

    public ZeroSentinelCommand(ZeroSentinel plugin) {
        this.plugin = plugin;
    }

    private MessageManager msg() {
        return plugin.getMessageManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!check(sender, Permissions.GUI)) {
                    return true;
                }
                plugin.getGuiManager().openMain(player, 0);
            } else {
                help(sender, label);
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                if (check(sender, Permissions.HELP)) {
                    help(sender, label);
                }
            }
            case "version" -> {
                if (check(sender, Permissions.VERSION)) {
                    version(sender);
                }
            }
            case "reload" -> {
                if (check(sender, Permissions.RELOAD)) {
                    plugin.reload();
                    msg().send(sender, "command.reloaded");
                }
            }
            case "record" -> {
                if (check(sender, Permissions.RECORD)) {
                    record(sender, label, args);
                }
            }
            case "lookup" -> {
                if (check(sender, Permissions.LOOKUP)) {
                    lookup(sender, label, args);
                }
            }
            case "verdict" -> {
                if (check(sender, Permissions.VERDICT)) {
                    verdict(sender, label, args);
                }
            }
            case "debug" -> {
                if (check(sender, Permissions.DEBUG)) {
                    debug(sender, label, args);
                }
            }
            case "versions" -> {
                if (check(sender, Permissions.VERSIONS)) {
                    versions(sender);
                }
            }
            case "trust" -> trust(sender, label, args);
            default -> msg().send(sender, "command.unknown", "label", label);
        }
        return true;
    }

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        msg().send(sender, "command.no-permission");
        return false;
    }

    private void help(CommandSender sender, String label) {
        msg().send(sender, "help.header", "version", plugin.getPluginMeta().getVersion());
        if (sender.hasPermission(Permissions.GUI)) {
            msg().send(sender, "help.gui", "label", label);
        }
        if (sender.hasPermission(Permissions.RECORD)) {
            msg().send(sender, "help.record", "label", label);
            msg().send(sender, "help.record-stop", "label", label);
        }
        if (sender.hasPermission(Permissions.LOOKUP)) {
            msg().send(sender, "help.lookup", "label", label);
        }
        if (sender.hasPermission(Permissions.VERDICT)) {
            msg().send(sender, "help.verdict", "label", label);
        }
        if (sender.hasPermission(Permissions.DEBUG)) {
            msg().send(sender, "help.debug", "label", label);
            msg().send(sender, "help.debug-violation", "label", label);
        }
        if (sender.hasPermission(Permissions.VERSIONS)) {
            msg().send(sender, "help.versions", "label", label);
        }
        if (sender.hasPermission(Permissions.TRUST)) {
            msg().send(sender, "help.trust", "label", label);
        }
        if (sender.hasPermission(Permissions.TRUST_ADMIN)) {
            msg().send(sender, "help.trust-admin", "label", label);
        }
        if (sender.hasPermission(Permissions.RELOAD)) {
            msg().send(sender, "help.reload", "label", label);
        }
        if (sender.hasPermission(Permissions.VERSION)) {
            msg().send(sender, "help.version", "label", label);
        }
    }

    // ---------------- version ----------------

    private void version(CommandSender sender) {
        msg().send(sender, "update.checking");
        plugin.getUpdateChecker().check(result -> {
            switch (result.status()) {
                case OUTDATED -> msg().send(sender, "update.outdated", "current", result.current(),
                        "latest", result.latest(), "url", UpdateChecker.RELEASES_URL);
                case UP_TO_DATE -> msg().send(sender, "update.up-to-date", "current", result.current());
                case DEV_BUILD -> msg().send(sender, "update.dev-build", "current", result.current(),
                        "latest", result.latest());
                case NO_RELEASE -> msg().send(sender, "update.no-release", "current", result.current());
                case FAILED -> msg().send(sender, "update.failed");
            }
        });
    }

    // ---------------- record ----------------

    private void record(CommandSender sender, String label, String[] args) {
        if (args.length >= 3 && args[1].equalsIgnoreCase("stop")) {
            if (args.length != 3) {
                msg().send(sender, "help.record-stop", "label", label);
                return;
            }
            if (args[2].equalsIgnoreCase(ALL)) {
                int stopped = 0;
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (plugin.getRecordingManager().stop(p.getUniqueId())) {
                        stopped++;
                    }
                }
                msg().send(sender, "record.all-stopped", "count", String.valueOf(stopped));
                return;
            }
            Player target = findPlayer(sender, args[2]);
            if (target == null) {
                return;
            }
            if (plugin.getRecordingManager().stop(target.getUniqueId())) {
                msg().send(sender, "record.stopped", "player", target.getName());
            } else {
                msg().send(sender, "record.not-recording", "player", target.getName());
            }
            return;
        }
        if (args.length < 2 || args.length > 3) {
            msg().send(sender, "help.record", "label", label);
            return;
        }
        int max = plugin.getConfigManager().getManualMaxSeconds();
        int seconds = plugin.getConfigManager().getGuiRecordSeconds();
        if (args.length == 3) {
            Integer parsed = parseInt(args[2]);
            if (parsed == null || parsed < 1) {
                msg().send(sender, "command.invalid-number", "input", MessageManager.stripInput(cut(args[2], 16)));
                return;
            }
            if (parsed > max) {
                msg().send(sender, "record.capped", "requested", String.valueOf(parsed), "max", String.valueOf(max));
            }
            seconds = Math.min(parsed, max);
        }
        if (args[1].equalsIgnoreCase(ALL)) {
            recordAll(sender, seconds);
            return;
        }
        Player target = findPlayer(sender, args[1]);
        if (target == null) {
            return;
        }
        startRecording(sender, target, seconds);
    }

    /** 所有線上玩家同時開始錄製 */
    private void recordAll(CommandSender sender, int seconds) {
        int started = 0;
        int already = 0;
        int skipped = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            switch (plugin.getRecordingManager().startManual(p.getUniqueId(), seconds)) {
                case STARTED -> started++;
                case ALREADY_RECORDING -> already++;
                case DISABLED -> {
                    msg().send(sender, "record.disabled");
                    return;
                }
                case NOT_TRACKED -> skipped++;
            }
        }
        msg().send(sender, "record.all-started", "started", String.valueOf(started),
                "already", String.valueOf(already), "skipped", String.valueOf(skipped),
                "seconds", String.valueOf(seconds),
                "buffer", String.valueOf(plugin.getConfigManager().getBufferSeconds()));
    }

    /** 指令與箱子 UI 共用 */
    public static void startRecording(ZeroSentinel plugin, CommandSender sender, Player target, int seconds) {
        MessageManager msg = plugin.getMessageManager();
        RecordingManager.StartResult result = plugin.getRecordingManager().startManual(target.getUniqueId(), seconds);
        switch (result) {
            case STARTED -> msg.send(sender, "record.started", "player", target.getName(),
                    "seconds", String.valueOf(seconds),
                    "buffer", String.valueOf(plugin.getConfigManager().getBufferSeconds()));
            case ALREADY_RECORDING -> msg.send(sender, "record.already", "player", target.getName());
            case DISABLED -> msg.send(sender, "record.disabled");
            case NOT_TRACKED -> msg.send(sender, "record.not-tracked", "player", target.getName());
        }
    }

    private void startRecording(CommandSender sender, Player target, int seconds) {
        startRecording(plugin, sender, target, seconds);
    }

    // ---------------- lookup ----------------

    private void lookup(CommandSender sender, String label, String[] args) {
        if (args.length != 2) {
            msg().send(sender, "help.lookup", "label", label);
            return;
        }
        String code = ErrorCode.normalize(args[1]);
        if (code == null) {
            msg().send(sender, "lookup.invalid-code", "input", MessageManager.stripInput(cut(args[1], 32)));
            return;
        }
        msg().send(sender, "lookup.searching");
        UUID senderId = sender instanceof Player p ? p.getUniqueId() : null;
        whenDone(plugin.getEventManager().lookupCode(code), opt -> {
            CommandSender target = resolveSender(sender, senderId);
            if (target == null) {
                return;
            }
            if (opt.isEmpty()) {
                msg().send(target, "lookup.not-found", "code", ErrorCode.display(code));
                return;
            }
            printLookup(target, opt.get(), code);
        }, sender, senderId);
    }

    private void printLookup(CommandSender sender, EventManager.Lookup lookup, String highlightCode) {
        EventRecord e = lookup.event();
        DateTimeFormatter fmt = plugin.getConfigManager().getTimeFormat();
        msg().send(sender, "lookup.header", "event", EventId.format(e.id()));
        if (highlightCode != null) {
            for (CodeRecord c : lookup.codes()) {
                if (c.code().equals(highlightCode)) {
                    msg().send(sender, "lookup.code-issued", "code", c.display(),
                            "time", EventFormat.time(fmt, c.created()), "type", c.type().name());
                }
            }
        }
        msg().send(sender, "lookup.player", "player", nullToDash(e.playerName()), "uuid", e.uuid().toString());
        msg().send(sender, "lookup.category", "category", e.category());
        msg().send(sender, "lookup.time", "created", EventFormat.time(fmt, e.created()),
                "last", EventFormat.time(fmt, e.lastSeen()));
        msg().send(sender, "lookup.violations", "count", String.valueOf(e.violations()));
        msg().send(sender, "lookup.verdict", "verdict", EventFormat.verdict(msg(), e.verdict()));
        if (e.verdict() != null && e.verdict() != Verdict.NONE) {
            msg().send(sender, "lookup.verdict-detail", "by", nullToDash(e.verdictBy()),
                    "time", EventFormat.time(fmt, e.verdictAt()), "note", nullToDash(e.verdictNote()));
        }
        if (e.recording() != null) {
            msg().send(sender, "lookup.recording", "file", e.recording());
        } else {
            msg().send(sender, "lookup.no-recording");
        }
        msg().send(sender, "lookup.codes", "count", String.valueOf(lookup.codes().size()),
                "codes", EventFormat.codes(msg(), lookup.codes(), 20));
    }

    // ---------------- verdict ----------------

    private void verdict(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            msg().send(sender, "help.verdict", "label", label);
            return;
        }
        Verdict verdict = Verdict.parseInput(args[2]);
        if (verdict == null) {
            msg().send(sender, "help.verdict", "label", label);
            return;
        }
        String note = null;
        if (args.length > 3) {
            note = MessageManager.stripInput(cut(String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)),
                    MAX_NOTE_LENGTH)).trim();
            if (note.isEmpty()) {
                note = null;
            }
        }
        String by = sender.getName();
        final String finalNote = note;
        UUID senderId = sender instanceof Player p ? p.getUniqueId() : null;

        CompletableFuture<Optional<Long>> idFuture;
        Long directId = EventId.parse(args[1]);
        if (directId != null && args[1].length() >= 13) {
            idFuture = CompletableFuture.completedFuture(Optional.of(directId));
        } else {
            String code = ErrorCode.normalize(args[1]);
            if (code == null) {
                msg().send(sender, "verdict.invalid-target", "input", MessageManager.stripInput(cut(args[1], 32)));
                return;
            }
            idFuture = plugin.getEventManager().lookupCode(code)
                    .thenApply(opt -> opt.map(l -> l.event().id()));
        }
        CompletableFuture<Optional<EventRecord>> result = idFuture.thenCompose(opt -> opt.isEmpty()
                ? CompletableFuture.completedFuture(Optional.<EventRecord>empty())
                : plugin.getEventManager().verdict(opt.get(), verdict, by, finalNote));
        whenDone(result, opt -> {
            CommandSender target = resolveSender(sender, senderId);
            if (target == null) {
                return;
            }
            if (opt.isEmpty()) {
                msg().send(target, "verdict.not-found");
                return;
            }
            msg().send(target, "verdict.done", "event", EventId.format(opt.get().id()),
                    "verdict", EventFormat.verdict(msg(), verdict));
        }, sender, senderId);
    }

    // ---------------- debug ----------------

    private void debug(CommandSender sender, String label, String[] args) {
        if (!plugin.getConfigManager().isDebugEnabled()) {
            msg().send(sender, "debug.disabled");
            return;
        }
        if (args.length != 3 || !(args[1].equalsIgnoreCase("flag") || args[1].equalsIgnoreCase("violation"))) {
            msg().send(sender, "help.debug", "label", label);
            msg().send(sender, "help.debug-violation", "label", label);
            return;
        }
        Player target = findPlayer(sender, args[2]);
        if (target == null) {
            return;
        }
        if (args[1].equalsIgnoreCase("violation")) {
            clre20.zeroSentinel.check.PlayerData d = plugin.getPlayerDataManager().get(target.getUniqueId());
            if (d == null) {
                msg().send(sender, "record.not-tracked", "player", target.getName());
                return;
            }
            var outcome = plugin.getClientReportCheck().violate(target, d, System.currentTimeMillis());
            msg().send(sender, "debug.violation", "player", target.getName(), "outcome", outcome.name(),
                    "trust", String.valueOf(plugin.getTrustManager().score(target.getUniqueId())));
            return;
        }
        EventManager.FlagResult flag = plugin.getEventManager().flag(target.getUniqueId(), target.getName(), "Debug");
        if (flag.isNew()) {
            plugin.getRecordingManager().startForEvent(target.getUniqueId(), flag.event().id());
        }
        CodeRecord code = plugin.getEventManager().issueCode(flag.event(), MessageType.WARN);
        msg().sendNoPrefix(target, "player.warn", "code", code.display());
        msg().send(sender, "debug.flagged", "player", target.getName(),
                "event", EventId.format(flag.event().id()), "code", code.display(),
                "new", msg().raw(flag.isNew() ? "debug.new" : "debug.merged"),
                "count", String.valueOf(flag.event().violations()));
    }

    // ---------------- versions ----------------

    private void versions(CommandSender sender) {
        java.util.TreeMap<Integer, Integer> javaCounts = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        int bedrock = 0;
        int total = 0;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            total++;
            clre20.zeroSentinel.check.PlayerData d = plugin.getPlayerDataManager().get(p.getUniqueId());
            boolean be = d != null ? d.bedrock() : clre20.zeroSentinel.compatibility.FloodgateHook.isBedrock(p.getUniqueId());
            if (be) {
                bedrock++;
                continue;
            }
            int protocol = d != null ? d.protocol() : clre20.zeroSentinel.util.Versions.protocol(p);
            javaCounts.merge(protocol, 1, Integer::sum);
        }
        msg().send(sender, "versions.header", "total", String.valueOf(total),
                "java", String.valueOf(total - bedrock), "bedrock", String.valueOf(bedrock));
        for (var e : javaCounts.entrySet()) {
            String name = clre20.zeroSentinel.util.Versions.name(e.getKey());
            msg().send(sender, "versions.line", "version", name != null ? name : msg().raw("versions.unknown",
                    "protocol", String.valueOf(e.getKey())), "protocol", String.valueOf(e.getKey()),
                    "count", String.valueOf(e.getValue()));
        }
        if (bedrock > 0) {
            msg().send(sender, "versions.bedrock", "count", String.valueOf(bedrock));
        }
    }

    // ---------------- trust ----------------

    /** 玩家名稱：Java 版 3～16 字元英數底線；基岩版（Floodgate）可能帶前綴，最長 32 字元 */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");

    private void trust(CommandSender sender, String label, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "help.trust-admin", "label", label);
                return;
            }
            if (!check(sender, Permissions.TRUST)) {
                return;
            }
            msg().send(sender, "trust.self", "trust", String.valueOf(plugin.getTrustManager().score(player.getUniqueId())),
                    "max", String.valueOf(plugin.getConfigManager().getTrustMax()));
            return;
        }
        if (!check(sender, Permissions.TRUST_ADMIN)) {
            return;
        }
        String targetName = args[1];
        if (!NAME.matcher(targetName).matches()) {
            msg().send(sender, "command.player-not-found", "player", MessageManager.stripInput(cut(targetName, 16)));
            return;
        }
        clre20.zeroSentinel.trust.TrustManager.Operation op = null;
        int value = 0;
        String reason = null;
        if (args.length >= 3) {
            op = switch (args[2].toLowerCase(Locale.ROOT)) {
                case "set" -> clre20.zeroSentinel.trust.TrustManager.Operation.SET;
                case "add" -> clre20.zeroSentinel.trust.TrustManager.Operation.ADD;
                case "take" -> clre20.zeroSentinel.trust.TrustManager.Operation.TAKE;
                default -> null;
            };
            if (op == null || args.length < 4) {
                msg().send(sender, "help.trust-admin", "label", label);
                return;
            }
            Integer parsed = parseInt(args[3]);
            if (parsed == null || parsed < 0 || parsed > plugin.getConfigManager().getTrustMax()) {
                msg().send(sender, "trust.invalid-value", "input", MessageManager.stripInput(cut(args[3], 16)),
                        "max", String.valueOf(plugin.getConfigManager().getTrustMax()));
                return;
            }
            value = parsed;
            if (args.length > 4) {
                reason = MessageManager.stripInput(cut(String.join(" ",
                        java.util.Arrays.copyOfRange(args, 4, args.length)), MAX_NOTE_LENGTH)).trim();
                if (reason.isEmpty()) {
                    reason = null;
                }
            }
        }
        final clre20.zeroSentinel.trust.TrustManager.Operation finalOp = op;
        final int finalValue = value;
        final String finalReason = reason;
        final String actor = sender.getName();
        UUID senderId = sender instanceof Player p ? p.getUniqueId() : null;

        CompletableFuture<Optional<UUID>> idFuture;
        Player online = plugin.getServer().getPlayerExact(targetName);
        if (online != null) {
            idFuture = CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
        } else {
            org.bukkit.OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(targetName);
            if (cached != null) {
                idFuture = CompletableFuture.completedFuture(Optional.of(cached.getUniqueId()));
            } else {
                idFuture = plugin.getTrustManager().findByName(targetName).thenApply(o -> o.map(r -> r.uuid()));
            }
        }
        CompletableFuture<Optional<clre20.zeroSentinel.trust.TrustRecord>> result = idFuture.thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            if (finalOp == null) {
                return plugin.getTrustManager().find(opt.get());
            }
            return plugin.getTrustManager().adminModify(opt.get(), targetName, finalOp, finalValue, actor, finalReason)
                    .thenApply(Optional::of);
        });
        whenDone(result, opt -> {
            CommandSender target = resolveSender(sender, senderId);
            if (target == null) {
                return;
            }
            if (opt.isEmpty()) {
                msg().send(target, "trust.not-found", "player", targetName);
                return;
            }
            var rec = opt.get();
            String shownName = rec.name() != null ? rec.name() : targetName;
            if (finalOp == null) {
                msg().send(target, "trust.view", "player", shownName, "trust", String.valueOf(rec.score()),
                        "max", String.valueOf(plugin.getConfigManager().getTrustMax()),
                        "status", msg().raw(plugin.getTrustManager().isDenied(rec.score()) ? "trust.status-denied" : "trust.status-ok"));
            } else {
                msg().send(target, "trust.modified", "player", shownName, "trust", String.valueOf(rec.score()));
                Player p = plugin.getServer().getPlayer(rec.uuid());
                if (p != null) {
                    msg().send(p, "trust.changed-notify", "trust", String.valueOf(rec.score()));
                }
            }
        }, sender, senderId);
    }

    // ---------------- 工具 ----------------

    private Player findPlayer(CommandSender sender, String name) {
        if (name.length() > 16) {
            msg().send(sender, "command.player-not-found", "player", MessageManager.stripInput(cut(name, 16)));
            return null;
        }
        Player p = plugin.getServer().getPlayerExact(name);
        if (p == null) {
            msg().send(sender, "command.player-not-found", "player", MessageManager.stripInput(name));
        }
        return p;
    }

    private <T> void whenDone(CompletableFuture<T> future, Consumer<T> onSuccess, CommandSender sender, UUID senderId) {
        future.whenComplete((value, error) -> Sync.run(plugin, () -> {
            if (error != null) {
                plugin.getLogger().warning("資料庫查詢失敗：" + error.getMessage());
                CommandSender target = resolveSender(sender, senderId);
                if (target != null) {
                    msg().send(target, "command.database-error");
                }
                return;
            }
            onSuccess.accept(value);
        }));
    }

    /** 非同步完成時，玩家可能已離線；主控台一律可用 */
    private CommandSender resolveSender(CommandSender original, UUID senderId) {
        if (senderId == null) {
            return original;
        }
        return plugin.getServer().getPlayer(senderId);
    }

    private static Integer parseInt(String s) {
        if (s.length() > 9) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String nullToDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    // ---------------- Tab 補全 ----------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            addIf(sender, out, Permissions.HELP, "help");
            addIf(sender, out, Permissions.VERSION, "version");
            addIf(sender, out, Permissions.RELOAD, "reload");
            addIf(sender, out, Permissions.RECORD, "record");
            addIf(sender, out, Permissions.LOOKUP, "lookup");
            addIf(sender, out, Permissions.VERDICT, "verdict");
            addIf(sender, out, Permissions.DEBUG, "debug");
            addIf(sender, out, Permissions.VERSIONS, "versions");
            addIf(sender, out, Permissions.TRUST, "trust");
            if (!out.contains("trust")) {
                addIf(sender, out, Permissions.TRUST_ADMIN, "trust");
            }
            return filter(out, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "record" -> {
                if (!sender.hasPermission(Permissions.RECORD)) {
                    return out;
                }
                if (args.length == 2) {
                    out.add("stop");
                    out.add(ALL);
                    out.addAll(playerNames());
                    return filter(out, args[1]);
                }
                if (args.length == 3) {
                    if (args[1].equalsIgnoreCase("stop")) {
                        List<String> names = playerNames();
                        names.add(0, ALL);
                        return filter(names, args[2]);
                    }
                    return filter(new ArrayList<>(SECONDS_SUGGESTIONS), args[2]);
                }
            }
            case "verdict" -> {
                if (sender.hasPermission(Permissions.VERDICT) && args.length == 3) {
                    return filter(new ArrayList<>(List.of("legit", "cheat")), args[2]);
                }
            }
            case "debug" -> {
                if (!sender.hasPermission(Permissions.DEBUG)) {
                    return out;
                }
                if (args.length == 2) {
                    return filter(new ArrayList<>(List.of("flag", "violation")), args[1]);
                }
                if (args.length == 3 && (args[1].equalsIgnoreCase("flag") || args[1].equalsIgnoreCase("violation"))) {
                    return filter(playerNames(), args[2]);
                }
            }
            case "trust" -> {
                if (!sender.hasPermission(Permissions.TRUST_ADMIN)) {
                    return out;
                }
                if (args.length == 2) {
                    return filter(playerNames(), args[1]);
                }
                if (args.length == 3) {
                    return filter(new ArrayList<>(List.of("set", "add", "take")), args[2]);
                }
                if (args.length == 4) {
                    return filter(new ArrayList<>(List.of("0", "100", "500")), args[3]);
                }
            }
            default -> {
                return out;
            }
        }
        return out;
    }

    private static void addIf(CommandSender sender, List<String> out, String perm, String value) {
        if (sender.hasPermission(perm)) {
            out.add(value);
        }
    }

    private List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
