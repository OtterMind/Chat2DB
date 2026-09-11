package ai.chat2db.community.tools.util.agent;

import java.util.List;
import java.util.Locale;

public final class AgentNativeTools {
    private AgentNativeTools() { }

    public static List<String> forPlatform(String osName) {
        String shell = osName.toLowerCase(Locale.ROOT).startsWith("windows") ? "powershell" : "bash";
        return List.of(shell, "read", "edit", "write", "grep", "find", "ls");
    }

    public static List<String> currentPlatform() {
        return forPlatform(System.getProperty("os.name", "unknown"));
    }
}
