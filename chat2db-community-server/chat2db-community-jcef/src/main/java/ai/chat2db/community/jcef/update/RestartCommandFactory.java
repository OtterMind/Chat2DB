package ai.chat2db.community.jcef.update;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

final class RestartCommandFactory {

    private static final String POSIX_WAIT_SCRIPT =
            "parent_pid=\"$1\"; shift; "
                    + "while kill -0 \"$parent_pid\" 2>/dev/null; do sleep 0.1; done; "
                    + "exec \"$@\"";

    private static final String MAC_WAIT_SCRIPT =
            "parent_pid=\"$1\"; app_bundle=\"$2\"; shift 2; "
                    + "while kill -0 \"$parent_pid\" 2>/dev/null; do sleep 0.1; done; "
                    + "sleep 0.3; "
                    + "exec /usr/bin/open -n \"$app_bundle\" --args \"$@\"";

    private RestartCommandFactory() {
    }

    static List<String> build(
            boolean windows,
            boolean mac,
            long parentPid,
            String launcherPath,
            String[] applicationArguments
    ) {
        if (windows) {
            return buildWindows(parentPid, launcherPath, applicationArguments);
        }
        if (mac) {
            return buildMac(parentPid, launcherPath, applicationArguments);
        }
        return buildPosix(parentPid, launcherPath, applicationArguments);
    }

    static List<String> buildMac(long parentPid, String launcherPath, String[] applicationArguments) {
        String appBundle = findMacAppBundle(launcherPath);
        if (appBundle == null) {
            return buildPosix(parentPid, launcherPath, applicationArguments);
        }
        List<String> command = new ArrayList<>();
        command.add("/bin/sh");
        command.add("-c");
        command.add(MAC_WAIT_SCRIPT);
        command.add("chat2db-restart");
        command.add(Long.toString(parentPid));
        command.add(appBundle);
        command.addAll(List.of(applicationArguments));
        return command;
    }

    static List<String> buildPosix(long parentPid, String launcherPath, String[] applicationArguments) {
        List<String> command = new ArrayList<>();
        command.add("/bin/sh");
        command.add("-c");
        command.add(POSIX_WAIT_SCRIPT);
        command.add("chat2db-restart");
        command.add(Long.toString(parentPid));
        command.add(launcherPath);
        command.addAll(List.of(applicationArguments));
        return command;
    }

    static List<String> buildWindows(long parentPid, String launcherPath, String[] applicationArguments) {
        String startProcess = "Start-Process -FilePath " + powerShellLiteral(launcherPath);
        if (applicationArguments.length > 0) {
            String arguments = List.of(applicationArguments).stream()
                    .map(RestartCommandFactory::windowsCommandLineArgument)
                    .collect(Collectors.joining(" "));
            startProcess += " -ArgumentList " + powerShellLiteral(arguments);
        }
        String script = "$ErrorActionPreference='Stop';"
                + "Wait-Process -Id " + parentPid + " -ErrorAction SilentlyContinue;"
                + startProcess;
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle",
                "Hidden",
                "-EncodedCommand",
                encodedScript
        );
    }

    private static String findMacAppBundle(String launcherPath) {
        Path current = Path.of(launcherPath).toAbsolutePath();
        while (current != null) {
            Path fileName = current.getFileName();
            if (fileName != null && fileName.toString().endsWith(".app")) {
                return current.toString();
            }
            current = current.getParent();
        }
        return null;
    }

    private static String powerShellLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String windowsCommandLineArgument(String value) {
        if (!value.isEmpty() && value.chars().noneMatch(character -> Character.isWhitespace(character) || character == '"')) {
            return value;
        }
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') {
                backslashes++;
                continue;
            }
            if (character == '"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
            } else {
                quoted.append("\\".repeat(backslashes)).append(character);
            }
            backslashes = 0;
        }
        quoted.append("\\".repeat(backslashes * 2)).append('"');
        return quoted.toString();
    }
}
