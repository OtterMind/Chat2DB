package ai.chat2db.community.web.api.config.console;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopBridgeRequestContextTest {

    @Test
    void marksOnlyTheCurrentBridgeInvocationAsActive() {
        assertFalse(DesktopBridgeRequestContext.isActive());

        DesktopBridgeRequestContext.invoke(() -> {
            assertTrue(DesktopBridgeRequestContext.isActive());
            DesktopBridgeRequestContext.requireActive();
            return null;
        });

        assertFalse(DesktopBridgeRequestContext.isActive());
        assertThrows(BusinessException.class, DesktopBridgeRequestContext::requireActive);
    }

    @Test
    void clearsContextWhenBridgeInvocationFails() {
        assertThrows(IllegalStateException.class, () -> DesktopBridgeRequestContext.invoke(() -> {
            throw new IllegalStateException("failed");
        }));

        assertFalse(DesktopBridgeRequestContext.isActive());
    }
}
