package clre20.zeroSentinel.event;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ErrorCodeTest {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    @Test
    void generatedCodesAreValidAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            String code = ErrorCode.generate();
            assertEquals(ErrorCode.LENGTH, code.length());
            assertEquals(code, ErrorCode.normalize(code));
            assertTrue(seen.add(code), "duplicate " + code);
        }
    }

    @Test
    void displayAndNormalizeRoundTrip() {
        String code = ErrorCode.generate();
        String shown = ErrorCode.display(code);
        assertEquals(10, shown.length());
        assertEquals('-', shown.charAt(4));
        assertEquals(code, ErrorCode.normalize(shown));
        assertEquals(code, ErrorCode.normalize(shown.toLowerCase()));
        assertEquals(code, ErrorCode.normalize(" " + shown.replace("-", " ") + " "));
    }

    @Test
    void detectsEverySingleCharacterError() {
        for (int n = 0; n < 2000; n++) {
            String code = ErrorCode.generate();
            for (int pos = 0; pos < code.length(); pos++) {
                for (char c : ALPHABET.toCharArray()) {
                    if (c == code.charAt(pos)) {
                        continue;
                    }
                    String typo = code.substring(0, pos) + c + code.substring(pos + 1);
                    // 校驗位為 mod 31：值 0 與 31（'0' 與 'Z'）在同一位置互換的情況無法偵測，其餘都必須偵測出來
                    int a = ALPHABET.indexOf(code.charAt(pos));
                    int b = ALPHABET.indexOf(c);
                    if (Math.abs(a - b) == 31) {
                        continue;
                    }
                    assertNull(ErrorCode.normalize(typo), "undetected typo " + code + " -> " + typo);
                }
            }
        }
    }

    @Test
    void rejectsBadInput() {
        assertNull(ErrorCode.normalize(null));
        assertNull(ErrorCode.normalize(""));
        assertNull(ErrorCode.normalize("ABC"));
        assertNull(ErrorCode.normalize("ABCD-EFGH-JK"));
        assertNull(ErrorCode.normalize("ABCD-EFG!1"));
        assertNull(ErrorCode.normalize("U".repeat(9)));
        assertNull(ErrorCode.normalize("A".repeat(100)));
        assertNull(ErrorCode.normalize("§aABCD-EFGH"));
    }

    @Test
    void ambiguousCharactersAreMapped() {
        String code = ErrorCode.generate();
        String replaced = code.replace('0', 'O').replace('1', 'I');
        assertEquals(code, ErrorCode.normalize(replaced));
    }

    @Test
    void eventIdRoundTrip() {
        for (int i = 0; i < 10_000; i++) {
            long id = EventId.next();
            String s = EventId.format(id);
            assertEquals(14, s.length());
            assertEquals(id, EventId.parse(s));
            assertEquals(id, EventId.parse(s.toLowerCase()));
            assertTrue(Math.abs(EventId.timestamp(id) - System.currentTimeMillis()) < 5000);
        }
        assertNull(EventId.parse("E123"));
        assertNull(EventId.parse("not-an-id-at-all"));
        assertNull(EventId.parse(null));
    }
}
