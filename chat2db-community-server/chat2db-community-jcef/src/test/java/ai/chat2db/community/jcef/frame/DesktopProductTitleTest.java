package ai.chat2db.community.jcef.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopProductTitleTest {

    @Test
    void shouldResolveWindowsCommunityTitle() {
        assertEquals("Chat2DB Community", DesktopProductTitle.resolve(true, true, false));
    }

    @Test
    void shouldKeepNonWindowsCommunityTitleUnchanged() {
        assertEquals("Chat2DB Pro", DesktopProductTitle.resolve(false, true, false));
    }

    @Test
    void shouldResolveLocalTitle() {
        assertEquals("Chat2DB Local", DesktopProductTitle.resolve(true, false, true));
    }

    @Test
    void shouldResolveProTitle() {
        assertEquals("Chat2DB Pro", DesktopProductTitle.resolve(true, false, false));
    }
}
