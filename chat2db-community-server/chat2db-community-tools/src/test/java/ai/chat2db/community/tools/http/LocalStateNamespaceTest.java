package ai.chat2db.community.tools.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalStateNamespaceTest {

    private final String originalNamespace = System.getProperty(LocalStateNamespace.PROPERTY_NAME);

    @AfterEach
    void restoreNamespace() {
        if (originalNamespace == null) {
            System.clearProperty(LocalStateNamespace.PROPERTY_NAME);
        } else {
            System.setProperty(LocalStateNamespace.PROPERTY_NAME, originalNamespace);
        }
    }

    @Test
    void communityNamespaceIsTheDefault() {
        System.clearProperty(LocalStateNamespace.PROPERTY_NAME);

        assertEquals("community", LocalStateNamespace.current());
        assertEquals("chat2db-community-cookie-release", LocalStateNamespace.fileName("cookie", "release"));
    }

    @Test
    void configuredNamespaceControlsStateFileNames() {
        System.setProperty(LocalStateNamespace.PROPERTY_NAME, "enterprise");

        assertEquals("enterprise", LocalStateNamespace.current());
        assertEquals("chat2db-enterprise-header-release", LocalStateNamespace.fileName("header", "release"));
        assertEquals("chat2db-enterprise-cookie-release", LocalStateNamespace.fileName("cookie", "release"));
    }

    @Test
    void invalidNamespaceCannotEscapeTheCacheDirectory() {
        System.setProperty(LocalStateNamespace.PROPERTY_NAME, "../outside");

        assertThrows(IllegalArgumentException.class, LocalStateNamespace::current);
    }
}
