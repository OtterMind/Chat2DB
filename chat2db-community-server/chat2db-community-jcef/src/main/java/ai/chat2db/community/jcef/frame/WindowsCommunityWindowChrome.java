package ai.chat2db.community.jcef.frame;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;

final class WindowsCommunityWindowChrome {

    static final int TITLE_BAR_HEIGHT = 36;
    private static final int WEB_APP_MENU_RESERVED_WIDTH = 220;
    private static final int WEB_TRAILING_ACTIONS_RESERVED_WIDTH = 320;

    private WindowsCommunityWindowChrome() {
    }

    static boolean isEnabled(boolean windows, boolean community) {
        return windows && community;
    }

    static void configureRootPane(JRootPane rootPane) {
        rootPane.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT, true);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        // The visible window controls are rendered by the web title bar. Keeping FlatLaf's
        // invisible controls would create overlapping native hit targets on Windows.
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICONIFFY, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_MAXIMIZE, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_CLOSE, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT, TITLE_BAR_HEIGHT);
    }

    static void configureBrowserComponent(Component browserComponent) {
        if (!(browserComponent instanceof JComponent component)) {
            return;
        }
        Function<Point, Boolean> captionHitTest = point -> isCaptionPoint(point, component.getWidth());
        component.putClientProperty(FlatClientProperties.COMPONENT_TITLE_BAR_CAPTION, captionHitTest);
    }

    static void installWindowDragging(Frame frame, Component browserComponent) {
        BrowserWindowDragHandler handler = new BrowserWindowDragHandler(frame, browserComponent);
        browserComponent.addMouseListener(handler);
        browserComponent.addMouseMotionListener(handler);
    }

    static boolean isCaptionPoint(Point point, int width) {
        if (point == null || width <= 0 || point.y < 0 || point.y >= TITLE_BAR_HEIGHT) {
            return false;
        }
        return point.x >= WEB_APP_MENU_RESERVED_WIDTH
                && point.x < width - WEB_TRAILING_ACTIONS_RESERVED_WIDTH;
    }

    static Point draggedWindowLocation(Point windowOrigin, Point pointerOrigin, Point pointerCurrent) {
        return new Point(
                windowOrigin.x + pointerCurrent.x - pointerOrigin.x,
                windowOrigin.y + pointerCurrent.y - pointerOrigin.y
        );
    }

    private static final class BrowserWindowDragHandler extends MouseAdapter {

        private final Frame frame;
        private final Component browserComponent;
        private Point pointerOrigin;
        private Point windowOrigin;

        private BrowserWindowDragHandler(Frame frame, Component browserComponent) {
            this.frame = frame;
            this.browserComponent = browserComponent;
        }

        @Override
        public void mousePressed(MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event)
                    || !isCaptionPoint(event.getPoint(), browserComponent.getWidth())) {
                return;
            }
            pointerOrigin = event.getLocationOnScreen();
            windowOrigin = frame.getLocation();
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (pointerOrigin == null
                    || windowOrigin == null
                    || (event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0) {
                return;
            }
            frame.setLocation(draggedWindowLocation(windowOrigin, pointerOrigin, event.getLocationOnScreen()));
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            pointerOrigin = null;
            windowOrigin = null;
        }
    }
}
