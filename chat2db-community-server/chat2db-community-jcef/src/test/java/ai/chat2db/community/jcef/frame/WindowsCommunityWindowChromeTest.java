package ai.chat2db.community.jcef.frame;

import com.formdev.flatlaf.FlatClientProperties;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import java.awt.Point;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsCommunityWindowChromeTest {

    @Test
    void shouldOnlyEnableIntegratedChromeForWindowsCommunity() {
        assertTrue(WindowsCommunityWindowChrome.isEnabled(true, true));
        assertFalse(WindowsCommunityWindowChrome.isEnabled(true, false));
        assertFalse(WindowsCommunityWindowChrome.isEnabled(false, true));
    }

    @Test
    void shouldConfigureFlatLafFullWindowContent() {
        JRootPane rootPane = new JRootPane();

        WindowsCommunityWindowChrome.configureRootPane(rootPane);

        assertEquals(true, rootPane.getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT));
        assertEquals(true, rootPane.getClientProperty(FlatClientProperties.MENU_BAR_EMBEDDED));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE));
        assertEquals(
                WindowsCommunityWindowChrome.TITLE_BAR_HEIGHT,
                rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldOnlyTreatTheTopCenterAsNativeCaption() {
        JPanel browserComponent = new JPanel();
        browserComponent.setSize(1000, 800);

        WindowsCommunityWindowChrome.configureBrowserComponent(browserComponent);

        Function<Point, Boolean> captionHitTest = (Function<Point, Boolean>) browserComponent.getClientProperty(
                FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION
        );
        assertFalse(captionHitTest.apply(new Point(100, 10)));
        assertTrue(captionHitTest.apply(new Point(500, 10)));
        assertFalse(captionHitTest.apply(new Point(900, 10)));
        assertFalse(captionHitTest.apply(new Point(500, WindowsCommunityWindowChrome.TITLE_BAR_HEIGHT)));
    }

    @Test
    void shouldIgnoreNonSwingBrowserComponents() {
        WindowsCommunityWindowChrome.configureBrowserComponent(new java.awt.Canvas());
    }
}
