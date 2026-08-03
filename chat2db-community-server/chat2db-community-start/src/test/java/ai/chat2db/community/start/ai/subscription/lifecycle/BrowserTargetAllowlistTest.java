package ai.chat2db.community.start.ai.subscription.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserTargetAllowlistTest {

    @Test
    void acceptsOnlyHttpsAllowlistedHosts() {
        assertTrue(BrowserTargetAllowlist.isAllowed("https://chatgpt.com/oauth"));
        assertTrue(BrowserTargetAllowlist.isAllowed("https://auth.openai.com/device"));
        assertFalse(BrowserTargetAllowlist.isAllowed("http://chatgpt.com/oauth"));
        assertFalse(BrowserTargetAllowlist.isAllowed("https://evil.example/oauth"));
        assertFalse(BrowserTargetAllowlist.isAllowed("https://chatgpt.com.evil.example/oauth"));
        assertFalse(BrowserTargetAllowlist.isAllowed(null));
        assertFalse(BrowserTargetAllowlist.isAllowed("not-a-url"));
    }
}
