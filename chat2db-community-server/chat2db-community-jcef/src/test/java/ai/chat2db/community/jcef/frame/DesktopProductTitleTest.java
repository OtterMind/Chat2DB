package ai.chat2db.community.jcef.frame;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopProductTitleTest {

    @Test
    void shouldResolveConfiguredProductTitle() {
        assertEquals("Chat2DB Community", DesktopProductTitle.resolve());
    }
}
