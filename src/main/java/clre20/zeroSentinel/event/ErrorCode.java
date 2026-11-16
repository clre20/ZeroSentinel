package clre20.zeroSentinel.event;

import clre20.zeroSentinel.util.Crockford;

import java.security.SecureRandom;

/**
 * 給玩家看的錯誤碼。
 * <ul>
 *     <li>8 個完全隨機的字元（{@link SecureRandom}），與檢查名稱、分類、時間、事件 ID 都沒有關聯。</li>
 *     <li>第 9 個字元是檢查碼（權重和 mod 31），玩家抄錯一個字或對調相鄰兩字時可以偵測出來。</li>
 *     <li>內部儲存為 9 個字元的大寫字串，顯示為 {@code XXXX-XXXXC}。</li>
 * </ul>
 */
public final class ErrorCode {

    public static final int RANDOM_LENGTH = 8;
    public static final int LENGTH = RANDOM_LENGTH + 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ErrorCode() {
    }

    /** 產生一組新的錯誤碼（內部格式，9 個字元） */
    public static String generate() {
        char[] out = new char[LENGTH];
        // 一次取 40 位元，剛好是 8 個字元
        long bits = RANDOM.nextLong();
        int[] values = new int[RANDOM_LENGTH];
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            values[i] = (int) (bits & 31);
            bits >>>= 5;
            out[i] = Crockford.encode(values[i]);
        }
        out[RANDOM_LENGTH] = Crockford.encode(checksum(values));
        return new String(out);
    }

    /** 內部格式轉為顯示格式 {@code XXXX-XXXXC} */
    public static String display(String code) {
        if (code == null || code.length() != LENGTH) {
            return String.valueOf(code);
        }
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    /**
     * 正規化玩家或管理員輸入的錯誤碼：不分大小寫、可省略 {@code -} 與空白，並驗證檢查碼。
     *
     * @return 內部格式；格式或檢查碼錯誤時回傳 null
     */
    public static String normalize(String input) {
        if (input == null) {
            return null;
        }
        // 限制輸入長度，避免異常輸入
        if (input.length() > 32) {
            return null;
        }
        StringBuilder sb = new StringBuilder(LENGTH);
        int[] values = new int[LENGTH];
        int n = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '-' || c == ' ') {
                continue;
            }
            int v = Crockford.decode(c);
            if (v < 0 || n >= LENGTH) {
                return null;
            }
            values[n++] = v;
            sb.append(Crockford.encode(v));
        }
        if (n != LENGTH) {
            return null;
        }
        int[] randomPart = new int[RANDOM_LENGTH];
        System.arraycopy(values, 0, randomPart, 0, RANDOM_LENGTH);
        if (checksum(randomPart) != values[RANDOM_LENGTH]) {
            return null;
        }
        return sb.toString();
    }

    /** 權重 1~8，模數 31（質數），可偵測所有單一字元錯誤與大部分相鄰對調 */
    static int checksum(int[] values) {
        int sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += (i + 1) * values[i];
        }
        return sum % 31;
    }
}
