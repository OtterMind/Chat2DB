package ai.chat2db.community.jcef.frame;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import java.awt.Component;
import java.awt.Point;
import java.util.function.Function;

final class WindowsCommunityWindowChrome {

    static final int TITLE_BAR_HEIGHT = 36;
    private static final int WEB_APP_MENU_RESERVED_WIDTH = 40;
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

    static boolean isCaptionPoint(Point point, int width) {
        if (point == null || width <= 0 || point.y < 0 || point.y >= TITLE_BAR_HEIGHT) {
            return false;
        }
        return point.x >= WEB_APP_MENU_RESERVED_WIDTH
                && point.x < width - WEB_TRAILING_ACTIONS_RESERVED_WIDTH;
    }
}
