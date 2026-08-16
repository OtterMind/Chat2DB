package ai.chat2db.community.web.api.config.web.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsFilterTest {

    @Test
    void communityAllowsOnlyKnownFrontendOriginsOrMissingOrigin() {
        assertTrue(CorsFilter.allowCommunityOrigin(null));
        assertTrue(CorsFilter.allowCommunityOrigin(""));
        assertTrue(CorsFilter.allowCommunityOrigin("http://127.0.0.1:8888"));
        assertTrue(CorsFilter.allowCommunityOrigin("http://127.0.0.1:8889"));
        assertTrue(CorsFilter.allowCommunityOrigin("http://localhost:10825"));

        assertFalse(CorsFilter.allowCommunityOrigin("https://example.com"));
        assertFalse(CorsFilter.allowCommunityOrigin("http://127.0.0.1:3000"));
    }
}
