package ai.chat2db.community.web.api.config.web.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsFilterTest {

    @Test
    void releaseAllowsOnlyKnownFrontendOriginsOrMissingOrigin() {
        assertTrue(CorsFilter.allowCommunityOrigin(null, false));
        assertTrue(CorsFilter.allowCommunityOrigin("", false));
        assertTrue(CorsFilter.allowCommunityOrigin("http://127.0.0.1:8888", false));
        assertTrue(CorsFilter.allowCommunityOrigin("http://127.0.0.1:8889", false));
        assertTrue(CorsFilter.allowCommunityOrigin("http://localhost:10825", false));

        assertFalse(CorsFilter.allowCommunityOrigin("https://example.com", false));
        assertFalse(CorsFilter.allowCommunityOrigin("http://127.0.0.1:3000", false));
    }

    @Test
    void developmentAllowsAllOrigins() {
        assertTrue(CorsFilter.allowCommunityOrigin("https://example.com", true));
        assertTrue(CorsFilter.allowCommunityOrigin("http://127.0.0.1:3000", true));
    }
}
