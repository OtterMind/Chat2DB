package ai.chat2db.community.domain.core.impl.ai;

import ai.chat2db.community.domain.api.model.ai.AiBusinessContextResult;
import ai.chat2db.community.domain.api.model.request.ai.AiBusinessContextBuildRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiBusinessContextServiceImplTest {

    @Test
    void communityDoesNotResolveEnterpriseKnowledge() {
        AiBusinessContextResult result = new AiBusinessContextServiceImpl().resolve(new AiBusinessContextBuildRequest());
        assertNull(result.getStructuredContext());
        assertTrue(result.getSelectedKnowledge().isEmpty());
    }
}
