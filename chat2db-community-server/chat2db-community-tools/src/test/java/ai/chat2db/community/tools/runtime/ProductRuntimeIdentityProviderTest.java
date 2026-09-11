package ai.chat2db.community.tools.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductRuntimeIdentityProviderTest {

    @Test
    void fallsBackToCommunityIdentityWithoutAProductExtension() {
        ProductRuntimeIdentity identity = ProductRuntimeIdentityProvider.current();

        assertTrue(identity.communityRuntime());
        assertFalse(identity.offlineRuntime());
        assertEquals("Chat2DB Community", identity.displayName());
        assertEquals("chat2db-community", identity.protocolScheme());
        assertEquals(".chat2db-community", identity.stateDirectoryName());
        assertEquals("runtime_config_test.json", identity.runtimeConfigFileName("test"));
    }
}
