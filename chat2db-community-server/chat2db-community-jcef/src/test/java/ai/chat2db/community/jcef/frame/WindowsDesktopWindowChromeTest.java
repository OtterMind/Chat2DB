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

class WindowsDesktopWindowChromeTest {

    @Test
    void shouldEnableIntegratedChromeForEveryWindowsDesktopProduct() {
        assertTrue(WindowsDesktopWindowChrome.isEnabled(true));
        assertFalse(WindowsDesktopWindowChrome.isEnabled(false));
    }

    @Test
    void shouldConfigureFlatLafFullWindowContent() {
        JRootPane rootPane = new JRootPane();

        WindowsDesktopWindowChrome.configureRootPane(rootPane);

        assertEquals(true, rootPane.getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICONIFFY));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_MAXIMIZE));
        assertEquals(false, rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_SHOW_CLOSE));
        assertEquals(34, WindowsDesktopWindowChrome.TITLE_BAR_HEIGHT);
        assertEquals(
                WindowsDesktopWindowChrome.TITLE_BAR_HEIGHT,
                rootPane.getClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldTreatTopBarSpaceBetweenWebControlsAsNativeCaption() {
        JPanel browserComponent = new JPanel();
        browserComponent.setSize(1000, 800);

        WindowsDesktopWindowChrome.configureBrowserComponent(browserComponent);

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
        assertFalse(captionHitTest.apply(new Point(500, WindowsDesktopWindowChrome.TITLE_BAR_HEIGHT)));
    }

    @Test
    void shouldIgnoreNonSwingBrowserComponents() {
        WindowsDesktopWindowChrome.configureBrowserComponent(new java.awt.Canvas());
    }

    @Test
    void shouldMoveWindowByThePointerDelta() {
        assertEquals(
                new Point(130, 175),
                WindowsDesktopWindowChrome.draggedWindowLocation(
                        new Point(100, 200),
                        new Point(400, 300),
                        new Point(430, 275)
                )
        );
    }
}
