package ai.chat2db.community.jcef.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/** Updates the version fields in the packaged macOS bundle plist, retaining a local backup. */
final class MacBundleVersionFile {

    private MacBundleVersionFile() {
    }

    static void update(Path applicationDirectory, String newVersion, Consumer<String> info, Consumer<Exception> error) {
        Path plist = applicationDirectory.resolve("../info.plist").normalize();
        info.accept("Start updating the version number in the file: " + plist);
        info.accept("new app version: " + newVersion);
        try {
            if (!Files.exists(plist) || !Files.isReadable(plist)) {
                info.accept("Error: The file does not exist or is unreadable: " + plist);
                return;
            }
            Path backupFile = plist.resolveSibling(plist.getFileName() + ".bak");
            Files.copy(plist, backupFile, StandardCopyOption.REPLACE_EXISTING);
            String content = Files.readString(plist, StandardCharsets.UTF_8);
            String shortVersionRegex = "(<key>CFBundleShortVersionString</key>\\s*<string>)[^<]+(</string>)";
            String bundleVersionRegex = "(<key>CFBundleVersion</key>\\s*<string>)[^<]+(</string>)";
            String updatedContent = content.replaceAll(shortVersionRegex, "$1" + newVersion + "$2")
                    .replaceAll(bundleVersionRegex, "$1" + newVersion + "$2");
            Files.writeString(plist, updatedContent, StandardCharsets.UTF_8);
            info.accept("The version number in the file has been updated");
        } catch (IOException exception) {
            error.accept(exception);
        }
    }
}
