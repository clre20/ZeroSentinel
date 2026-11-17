package clre20.zeroSentinel.command;

/** 權限節點（皆已在 plugin.yml 註冊，預設只有 OP 擁有） */
public final class Permissions {

    public static final String ADMIN = "zerosentinel.admin";
    public static final String GUI = "zerosentinel.gui";
    public static final String HELP = "zerosentinel.help";
    public static final String VERSION = "zerosentinel.version";
    public static final String RELOAD = "zerosentinel.reload";
    public static final String RECORD = "zerosentinel.record";
    public static final String LOOKUP = "zerosentinel.lookup";
    public static final String VERDICT = "zerosentinel.verdict";
    public static final String DEBUG = "zerosentinel.debug";
    public static final String ALERTS = "zerosentinel.alerts";
    public static final String VERSIONS = "zerosentinel.versions";
    /** 查看自己的信用點數（預設所有人） */
    public static final String TRUST = "zerosentinel.trust";
    /** 查看與修改他人的信用點數 */
    public static final String TRUST_ADMIN = "zerosentinel.trust.admin";

    private Permissions() {
    }
}
