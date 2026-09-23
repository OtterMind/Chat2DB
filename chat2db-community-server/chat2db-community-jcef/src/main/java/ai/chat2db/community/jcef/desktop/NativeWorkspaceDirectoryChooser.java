package ai.chat2db.community.jcef.desktop;

import ai.chat2db.community.tools.exception.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Opens the host's folder dialog without requiring a JCEF window or an AWT display. */
public final class NativeWorkspaceDirectoryChooser {
    private NativeWorkspaceDirectoryChooser() { }

    public static String choose() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command;
        if (os.startsWith("windows")) {
            command = List.of("powershell.exe", "-NoProfile", "-STA", "-Command", """
                    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
                    $shell = New-Object -ComObject Shell.Application
                    $folder = $shell.BrowseForFolder(0, 'Select folder', 0x51, 0)
                    if ($folder) { [Console]::Write($folder.Self.Path) }
                    """);
        } else if (os.contains("mac") || os.equals("darwin")) {
            command = List.of("/usr/bin/osascript", "-e", """
                    try
                        tell application "System Events"
                            activate
                            set selectedFolder to choose folder
                        end tell
                        return POSIX path of selectedFolder
                    on error number -128
                        return ""
                    end try
                    """);
        } else {
            command = List.of("zenity", "--file-selection", "--directory");
        }
        try {
            Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                String selected = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                int exit = process.waitFor();
                if (exit == 1 && !os.startsWith("windows") && !os.contains("mac")) return null;
                if (exit != 0) throw new BusinessException("agent.directory.picker.failed");
                return selected.isEmpty() ? null : selected;
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BusinessException("agent.directory.picker.failed");
        } catch (IOException error) {
            throw new BusinessException("agent.directory.picker.failed");
        }
    }
}
