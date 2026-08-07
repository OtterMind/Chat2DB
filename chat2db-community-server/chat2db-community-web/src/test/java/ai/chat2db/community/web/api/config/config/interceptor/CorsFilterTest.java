package ai.chat2db.community.web.api.config.web.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsFilterTest {

    @Test
    void releaseAllowsOnlyKnownFrontendOriginsOrMissingOrigin() {
        assertTrue(CorsFilter.allowCommunityOrigin(null, true));
        assertTrue(CorsFilter.allowCommunityOrigin("", true));
        assertTrue(CorsFilter.allowCommunityOrigin("http://127.0.0.1:8888", true));
        assertTrue(CorsFilter.allowCommunityOrigin("http://127.0.0.1:8889", true));
        assertTrue(CorsFilter.allowCommunityOrigin("http://localhost:10825", true));

        assertFalse(CorsFilter.allowCommunityOrigin("https://example.com", true));
        assertFalse(CorsFilter.allowCommunityOrigin("http://127.0.0.1:3000", true));
    }

    @Test
    void developmentAllowsLoopbackOriginsOnAnyPort() {
        assertTrue(CorsFilter.allowCommunityOrigin("http://127.0.0.1:8890", false));
        assertTrue(CorsFilter.allowCommunityOrigin("http://localhost:3000", false));
        assertTrue(CorsFilter.allowCommunityOrigin("http://[::1]:5173", false));

        assertFalse(CorsFilter.allowCommunityOrigin("https://example.com", false));
        assertFalse(CorsFilter.allowCommunityOrigin("http://127.0.0.1.example.com:8890", false));
        assertFalse(CorsFilter.allowCommunityOrigin("ftp://127.0.0.1:8890", false));
    }
}
