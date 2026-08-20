package ai.chat2db.community.runtime.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class WindowsCliInvocation {

    private WindowsCliInvocation() {
    }

    static List<String> claude(List<String> command) {
        return batch(command);
    }

    static List<String> openCode(List<String> command) {
        if (!windows() || !batchFile(command.get(0))) {
            return command;
        }
        Path shim = Path.of(command.get(0));
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        List<String> packages = arch.contains("aarch64") || arch.contains("arm64")
                ? List.of("opencode-windows-arm64", "opencode-windows-x64", "opencode-windows-x64-baseline")
                : List.of("opencode-windows-x64", "opencode-windows-x64-baseline", "opencode-windows-arm64");
        for (String packageName : packages) {
            Path nativeBinary = shim.getParent().resolve(Path.of("node_modules", "opencode-ai", "node_modules",
                    packageName, "bin", "opencode.exe"));
            if (Files.isRegularFile(nativeBinary)) {
                ArrayList<String> nativeCommand = new ArrayList<>(command);
                nativeCommand.set(0, nativeBinary.toString());
                return nativeCommand;
            }
        }
        throw new IllegalStateException("OpenCode Windows native binary was not found next to the npm shim");
    }

    static List<String> pi(List<String> command) {
        if (!windows() || !batchFile(command.get(0))) {
            return command;
        }
        Path shim = Path.of(command.get(0));
        Path script = shim.resolveSibling(stripExtension(shim.getFileName().toString()) + ".ps1");
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("Pi PowerShell launcher was not found next to the npm shim");
        }
        ArrayList<String> powershell = new ArrayList<>(List.of(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script.toString()));
        powershell.addAll(command.subList(1, command.size()));
        return powershell;
    }

    private static List<String> batch(List<String> command) {
        if (!windows() || !batchFile(command.get(0))) {
            return command;
        }
        ArrayList<String> wrapped = new ArrayList<>(List.of("cmd.exe", "/d", "/s", "/c"));
        wrapped.addAll(command);
        return wrapped;
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean batchFile(String executable) {
        String lower = executable.toLowerCase(Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    private static String stripExtension(String name) {
        int separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(0, separator);
    }
}
