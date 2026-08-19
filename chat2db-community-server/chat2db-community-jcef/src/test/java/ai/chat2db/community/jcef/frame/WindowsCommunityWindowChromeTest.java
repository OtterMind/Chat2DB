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
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICONIFFY));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_MAXIMIZE));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_CLOSE));
        assertEquals(34, WindowsCommunityWindowChrome.TITLE_BAR_HEIGHT);
        assertEquals(
                WindowsCommunityWindowChrome.TITLE_BAR_HEIGHT,
                rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldTreatTopBarSpaceBetweenWebControlsAsNativeCaption() {
        JPanel browserComponent = new JPanel();
        browserComponent.setSize(1000, 800);

        WindowsCommunityWindowChrome.configureBrowserComponent(browserComponent);

        Function<Point, Boolean> captionHitTest = (Function<Point, Boolean>) browserComponent.getClientProperty(
                FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION
        );
        assertFalse(captionHitTest.apply(new Point(20, 10)));
        assertFalse(captionHitTest.apply(new Point(100, 10)));
        assertTrue(captionHitTest.apply(new Point(220, 10)));
        assertTrue(captionHitTest.apply(new Point(500, 10)));
        assertTrue(captionHitTest.apply(new Point(679, 10)));
        assertFalse(captionHitTest.apply(new Point(680, 10)));
        assertFalse(captionHitTest.apply(new Point(900, 10)));
        assertFalse(captionHitTest.apply(new Point(500, WindowsCommunityWindowChrome.TITLE_BAR_HEIGHT)));
    }

    @Test
    void shouldIgnoreNonSwingBrowserComponents() {
        WindowsCommunityWindowChrome.configureBrowserComponent(new java.awt.Canvas());
    }

    @Test
    void shouldMoveWindowByThePointerDelta() {
        assertEquals(
                new Point(130, 175),
                WindowsCommunityWindowChrome.draggedWindowLocation(
                        new Point(100, 200),
                        new Point(400, 300),
                        new Point(430, 275)
                )
        );
    }
}
