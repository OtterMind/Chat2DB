package ai.chat2db.community.web.api.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The import template messages are served by two bundles: the backend bundle of
 * {@code chat2db-community-start} and the JCEF desktop bundle. A message added to one of them is
 * useless when the other locale is missing it, so both are checked here.
 */
class ImportTemplateMessageBundleTest {

    private static final List<String> MESSAGE_CODES = List.of(
            "import.template.nameRequired",
            "import.template.nameTooLong",
            "import.template.formatOptionsRequired",
            "import.template.storageFailed");

    private static final Map<String, List<String>> BUNDLES = bundles();

    @Test
    void everyServerBundleCarriesTheImportTemplateMessages() throws IOException {
        Path serverDirectory = serverDirectory();

        BUNDLES.forEach((directory, expectedFiles) -> expectedFiles.forEach(file -> {
            Path bundle = serverDirectory.resolve(directory).resolve(file);
            assertTrue(Files.isRegularFile(bundle), () -> "Missing message bundle " + bundle);
            Properties messages = readMessages(bundle);
            MESSAGE_CODES.forEach(code -> {
                String message = messages.getProperty(code);
                assertFalse(message == null || message.isBlank(), () -> bundle + " is missing " + code);
            });
            assertTrue(messages.getProperty("import.template.nameTooLong").contains("{0}"),
                    () -> bundle + " must keep the character limit placeholder");
        }));
    }

    private static Map<String, List<String>> bundles() {
        Map<String, List<String>> bundles = new LinkedHashMap<>();
        bundles.put("chat2db-community-start/src/main/resources/i18n", List.of(
                "messages.properties",
                "messages_en_US.properties",
                "messages_es_ES.properties",
                "messages_ja_JP.properties",
                "messages_ko_KR.properties",
                "messages_zh_CN.properties"));
        bundles.put("chat2db-community-jcef/src/main/resources/i18n", List.of(
                "messages.properties",
                "messages_en.properties",
                "messages_en_US.properties",
                "messages_es.properties",
                "messages_es_ES.properties",
                "messages_ja.properties",
                "messages_ja_JP.properties",
                "messages_ko.properties",
                "messages_ko_KR.properties",
                "messages_zh.properties",
                "messages_zh_CN.properties",
                "messages_zh_Hans.properties",
                "messages_zh_Hans_CN.properties"));
        return bundles;
    }

    /** Surefire runs inside a module, so the reactor directory is found from the working directory. */
    private static Path serverDirectory() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("chat2db-community-server");
            if (containsEveryBundle(candidate)) {
                return candidate;
            }
            if (containsEveryBundle(directory)) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not locate the server message bundles from "
                + System.getProperty("user.dir"));
    }

    private static boolean containsEveryBundle(Path directory) {
        return BUNDLES.keySet().stream().allMatch(bundle -> Files.isDirectory(directory.resolve(bundle)));
    }

    private static Properties readMessages(Path bundle) {
        Properties messages = new Properties();
        // Properties.load(Reader) decodes through the reader, so the non-ASCII messages stay intact.
        try (var reader = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
            messages.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + bundle, e);
        }
        return messages;
    }
}
