package ai.chat2db.community.start.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A message code with no entry is not reported as a missing translation - it is
 * returned to the client verbatim as "<code> : no message." - so the only thing
 * that keeps these codes honest is a check that every bundle defines them.
 */
class CustomDatabaseMessageBundleTest {

    private static final List<String> BUNDLES = List.of(
            "i18n/messages.properties",
            "i18n/messages_en_US.properties",
            "i18n/messages_zh_CN.properties",
            "i18n/messages_ja_JP.properties",
            "i18n/messages_ko_KR.properties",
            "i18n/messages_es_ES.properties");

    private static final List<String> CODES = List.of(
            "custom.database.dbTypeRequired",
            "custom.database.dbTypeConflict",
            "custom.database.driverRequired",
            "custom.database.driverClassRequired",
            "custom.database.driverJarRequired",
            "custom.database.urlRequired",
            "custom.database.notRegistered");

    @Test
    void everyBundleDefinesEveryCustomDatabaseCode() throws IOException {
        for (String bundle : BUNDLES) {
            Properties properties = load(bundle);
            for (String code : CODES) {
                String message = properties.getProperty(code);
                assertNotNull(message, bundle + " is missing " + code);
                assertTrue(!message.isBlank(), bundle + " has a blank message for " + code);
            }
        }
    }

    private Properties load(String bundle) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(bundle)) {
            assertNotNull(in, "bundle not found on the classpath: " + bundle);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
