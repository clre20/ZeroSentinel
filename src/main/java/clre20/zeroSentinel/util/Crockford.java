package clre20.zeroSentinel.util;

/**
 * Crockford Base32 字元集（排除 I、L、O、U，避免玩家抄寫時看錯）。
 */
public final class Crockford {

    public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int[] DECODE = new int[128];

    static {
        java.util.Arrays.fill(DECODE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            char c = ALPHABET.charAt(i);
            DECODE[c] = i;
            DECODE[Character.toLowerCase(c)] = i;
        }
        // Crockford 規則：容易混淆的字元自動對應
        DECODE['O'] = 0;
        DECODE['o'] = 0;
        DECODE['I'] = 1;
        DECODE['i'] = 1;
        DECODE['L'] = 1;
        DECODE['l'] = 1;
    }

    private Crockford() {
    }

    /** @return 0~31；不是合法字元時回傳 -1 */
    public static int decode(char c) {
        return c < 128 ? DECODE[c] : -1;
    }

    public static char encode(int value) {
        return ALPHABET.charAt(value & 31);
    }

    /** 64 位元整數編碼為固定 13 個字元 */
    public static String encodeLong(long value) {
        char[] out = new char[13];
        for (int i = 12; i >= 0; i--) {
            out[i] = encode((int) (value & 31));
            value >>>= 5;
        }
        return new String(out);
    }

    /**
     * 解碼 13 個字元的 64 位元整數。
     *
     * @return 解碼結果；格式不合法時回傳 null
     */
    public static Long decodeLong(String text) {
        if (text == null || text.length() != 13) {
            return null;
        }
        long value = 0;
        for (int i = 0; i < 13; i++) {
            int v = decode(text.charAt(i));
            if (v < 0) {
                return null;
            }
            if (i == 0 && v > 15) {
                // 13 字元 = 65 位元，第一個字元只能用到 4 位元
                return null;
            }
            value = (value << 5) | v;
        }
        return value;
    }
}
