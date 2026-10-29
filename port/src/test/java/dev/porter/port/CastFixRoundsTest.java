package dev.porter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CastFixRoundsTest {

    private static String log(String... errors) {
        StringBuilder b = new StringBuilder();
        for (String e : errors) {
            b.append("src/main/java/A.java:").append(e).append(System.lineSeparator()).append("    x").append(System.lineSeparator());
        }
        return b.toString();
    }

    @Test
    void anotherRoundWhenTheErrorsChangedEvenIfTheCountDidNot() {
        assertTrue(PortRunner.castfixProgress(log("3: error: cannot find symbol"), log("9: error: variable x might not have been initialized")));
        assertTrue(PortRunner.castfixProgress(log("3: error: a", "4: error: b"), log("4: error: b")));
    }

    @Test
    void stopsWhenJavacReportsTheSameErrors() {
        assertFalse(PortRunner.castfixProgress(log("3: error: a", "4: error: b"), log("4: error: b", "3: error: a")));
    }

    @Test
    void roundCapIsAGuard() {
        assertEquals(10, PortRunner.CASTFIX_ROUNDS);
    }
}
