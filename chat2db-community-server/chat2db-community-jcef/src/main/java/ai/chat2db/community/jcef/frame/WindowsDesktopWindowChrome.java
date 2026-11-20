package ai.chat2db.community.jcef.frame;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;

final class WindowsDesktopWindowChrome {

    static final int TITLE_BAR_HEIGHT = 34;
    private static final int WEB_APP_MENU_RESERVED_WIDTH = 220;
    private static final int WEB_TRAILING_ACTIONS_RESERVED_WIDTH = 320;

    private WindowsDesktopWindowChrome() {
    }

    static boolean isEnabled(boolean windows) {
        return windows;
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

    /**
     * Native Windows behavior when a maximized window is dragged: restore it
     * and place it so the pointer stays at the same relative position inside
     * the window, instead of leaving it pinned against the maximized bounds.
     */
    static Point restoredDragOrigin(Dimension maximizedSize, Dimension restoredSize,
            Point windowOrigin, Point pointerScreen) {
        double ratioX = maximizedSize.width <= 0 ? 0.5
                : (double) (pointerScreen.x - windowOrigin.x) / maximizedSize.width;
        double ratioY = maximizedSize.height <= 0 ? 0.5
                : (double) (pointerScreen.y - windowOrigin.y) / maximizedSize.height;
        return new Point(
                pointerScreen.x - (int) Math.round(ratioX * restoredSize.width),
                pointerScreen.y - (int) Math.round(ratioY * restoredSize.height)
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
            Point pointer = event.getLocationOnScreen();
            if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                Dimension maximizedSize = frame.getSize();
                frame.setExtendedState(frame.getExtendedState() & ~Frame.MAXIMIZED_BOTH);
                windowOrigin = restoredDragOrigin(maximizedSize, frame.getSize(), windowOrigin, pointer);
                pointerOrigin = pointer;
            }
            frame.setLocation(draggedWindowLocation(windowOrigin, pointerOrigin, pointer));
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            pointerOrigin = null;
            windowOrigin = null;
        }
    }
}
