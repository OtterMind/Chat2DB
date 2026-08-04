package ai.chat2db.community.tools.util;

import java.util.Collections;

import ai.chat2db.community.tools.model.HeaderAndCookies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression coverage for {@link ContextUtils#setHeaderAndCookies(Long, HeaderAndCookies)}:
 * a second call for the same orgId (e.g. re-login / token refresh) must overwrite the
 * first entry — previously a containsKey guard made the first entry permanent.
 */
class ContextUtilsTest {

    private static final Long ORG_ID = 424242L;

    @AfterEach
    void cleanUp() {
        ContextUtils.removeHeaderAndCookies(ORG_ID);
    }

    @Test
    void secondSetOverwritesFirstEntry() {
        HeaderAndCookies first = HeaderAndCookies.builder()
            .headers(Collections.singletonMap("k", "v1"))
            .cookies(new Cookie[] {new Cookie("session", "old")})
            .build();
        HeaderAndCookies second = HeaderAndCookies.builder()
            .headers(Collections.singletonMap("k", "v2"))
            .cookies(new Cookie[] {new Cookie("session", "new")})
            .build();
        ContextUtils.setHeaderAndCookies(ORG_ID, first);
        ContextUtils.setHeaderAndCookies(ORG_ID, second);
        HeaderAndCookies refreshed = ContextUtils.getHeaderAndCookies(ORG_ID);
        assertSame(second, refreshed);
        assertEquals("v2", refreshed.getHeaders().get("k"));
        assertEquals("new", refreshed.getCookies()[0].getValue());
    }
}
