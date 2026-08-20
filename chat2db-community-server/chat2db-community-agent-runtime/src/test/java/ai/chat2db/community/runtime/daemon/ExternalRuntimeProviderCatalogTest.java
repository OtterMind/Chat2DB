package ai.chat2db.community.runtime.daemon;

import ai.chat2db.community.domain.api.enums.agent.AgentRuntimeProviderEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalRuntimeProviderCatalogTest {

    @Test
    void dshDoesNotAdvertiseSessionResumeAcrossEphemeralWorkspaces() {
        var capabilities = ExternalRuntimeProviderCatalog.capabilities(AgentRuntimeProviderEnum.DSH);

        assertTrue(capabilities.contains("taskWorkspace"));
        assertFalse(capabilities.contains("sessionResume"));
    }
}
