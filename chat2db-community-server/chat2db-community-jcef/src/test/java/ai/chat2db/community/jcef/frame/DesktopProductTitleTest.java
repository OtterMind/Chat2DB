package ai.chat2db.community.jcef.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopProductTitleTest {

    @Test
    void shouldResolveCommunityTitle() {
        assertEquals("Chat2DB Community", DesktopProductTitle.resolve(true, false));
    }

    @Test
    void shouldResolveLocalTitle() {
        assertEquals("Chat2DB Local", DesktopProductTitle.resolve(false, true));
    }

    @Test
    void shouldResolveProTitle() {
        assertEquals("Chat2DB Pro", DesktopProductTitle.resolve(false, false));
    }
}
