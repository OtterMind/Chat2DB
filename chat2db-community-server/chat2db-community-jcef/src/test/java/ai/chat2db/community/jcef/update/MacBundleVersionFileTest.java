package ai.chat2db.community.jcef.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacBundleVersionFileTest {

    @Test
    void updatesBothBundleVersionFieldsAndKeepsBackup(@TempDir Path temporaryDirectory) throws Exception {
        Path applicationDirectory = Files.createDirectories(temporaryDirectory.resolve("Chat2DB.app/Contents/MacOS"));
        Path plist = applicationDirectory.resolve("../info.plist").normalize();
        Files.writeString(plist, """
                <key>CFBundleShortVersionString</key>
                <string>5.3.1</string>
                <key>CFBundleVersion</key>
                <string>5.3.1</string>
                """);
        List<String> messages = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();

        MacBundleVersionFile.update(applicationDirectory, "5.3.2", messages::add, errors::add);

        String updated = Files.readString(plist);
        assertTrue(updated.contains("<string>5.3.2</string>"));
        assertEquals(2, updated.split("<string>5.3.2</string>", -1).length - 1);
        assertEquals("""
                <key>CFBundleShortVersionString</key>
                <string>5.3.1</string>
                <key>CFBundleVersion</key>
                <string>5.3.1</string>
                """, Files.readString(plist.resolveSibling("info.plist.bak")));
        assertTrue(errors.isEmpty());
    }
}
