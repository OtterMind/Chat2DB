package ai.chat2db.community.jcef.frame;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import java.awt.Component;
import java.awt.Point;
import java.util.function.Function;

final class WindowsCommunityWindowChrome {

    static final int TITLE_BAR_HEIGHT = 36;
    private static final int CAPTION_START_PERCENT = 35;
    private static final int CAPTION_END_PERCENT = 65;

    private WindowsCommunityWindowChrome() {
    }

    static boolean isEnabled(boolean windows, boolean community) {
        return windows && community;
    }

    static void configureRootPane(JRootPane rootPane) {
        rootPane.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT, true);
        rootPane.putClientProperty(FlatClientProperties.MENU_BAR_EMBEDDED, true);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
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
        int captionStart = width * CAPTION_START_PERCENT / 100;
        int captionEnd = width * CAPTION_END_PERCENT / 100;
        return point.x >= captionStart && point.x < captionEnd;
    }
}
