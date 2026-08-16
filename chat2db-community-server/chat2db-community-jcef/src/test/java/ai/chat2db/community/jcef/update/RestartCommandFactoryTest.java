package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartCommandFactoryTest {

    @Test
    void macCommandWaitsForTheParentAndRelaunchesTheApplicationBundle() {
        List<String> command = RestartCommandFactory.buildMac(
                21L,
                "/Applications/Chat2DB Community.app/Contents/MacOS/Chat2DB Community",
                new String[]{"--profile", "value with spaces"}
        );

        assertEquals("/bin/sh", command.get(0));
        assertTrue(command.get(2).contains("kill -0"));
        assertTrue(command.get(2).contains("/usr/bin/open -n"));
        assertTrue(command.get(2).contains("sleep 0.3"));
        assertEquals("21", command.get(4));
        assertEquals("/Applications/Chat2DB Community.app", command.get(5));
        assertEquals("value with spaces", command.get(7));
    }

    @Test
    void macCommandFallsBackToPosixRestartOutsideAnApplicationBundle() {
        List<String> command = RestartCommandFactory.buildMac(
                21L,
                "/usr/bin/java",
                new String[]{"-jar", "chat2db.jar"}
        );

        assertTrue(command.get(2).contains("exec \"$@\""));
        assertEquals("21", command.get(4));
        assertEquals("/usr/bin/java", command.get(5));
        assertEquals("chat2db.jar", command.get(7));
    }

    @Test
    void posixCommandWaitsForTheParentAndPreservesLauncherArguments() {
        List<String> command = RestartCommandFactory.buildPosix(
                42L,
                "/Applications/Chat2DB Community.app/Contents/MacOS/Chat2DB Community",
                new String[]{"--profile", "value with spaces"}
        );

        assertEquals("/bin/sh", command.get(0));
        assertTrue(command.get(2).contains("kill -0"));
        assertTrue(command.get(2).contains("exec \"$@\""));
        assertEquals("42", command.get(4));
        assertEquals("/Applications/Chat2DB Community.app/Contents/MacOS/Chat2DB Community", command.get(5));
        assertEquals("value with spaces", command.get(7));
    }

    @Test
    void windowsCommandWaitsAndEscapesPowerShellLiterals() {
        List<String> command = RestartCommandFactory.buildWindows(
                84L,
                "C:\\Program Files\\Chat2DB's\\Chat2DB.exe",
                new String[]{"--profile", "value with spaces", "quoted\"value", "C:\\trailing path\\"}
        );
        String script = new String(
                Base64.getDecoder().decode(command.get(command.size() - 1)),
                StandardCharsets.UTF_16LE
        );

        assertEquals("powershell.exe", command.get(0));
        assertTrue(script.contains("Wait-Process -Id 84"));
        assertTrue(script.contains("'C:\\Program Files\\Chat2DB''s\\Chat2DB.exe'"));
        assertTrue(script.contains("-ArgumentList '--profile \"value with spaces\" \"quoted\\\"value\" \"C:\\trailing path\\\\\"'"));
    }
}
