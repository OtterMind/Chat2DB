package ai.chat2db.community.jcef.handler.mouse;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

import javax.swing.*;
import java.awt.*;

public class CursorHandler extends CefDisplayHandlerAdapter {

    private static final Object FORCED_CURSOR_LOCK = new Object();
    private static volatile Integer forcedCursorType;
    private static long forcedCursorSequence;

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        int effectiveCursorType = effectiveCursorType(cursorType);
        if (!isPredefinedCursorType(effectiveCursorType)) {
            return false;
        }
        applyCursor(browser, cursorType);
        return true;
    }

    public static void setForcedCursor(CefBrowser browser, String cssCursor, long sequence) {
        if (!updateForcedCursor(cssCursor, sequence)) {
            return;
        }
        applyCursor(browser, Cursor.DEFAULT_CURSOR);
    }

    static boolean updateForcedCursor(String cssCursor, long sequence) {
        synchronized (FORCED_CURSOR_LOCK) {
            if (sequence <= forcedCursorSequence) {
                return false;
            }
            forcedCursorSequence = sequence;
            forcedCursorType = toAwtCursorType(cssCursor);
            return true;
        }
    }

    static long currentForcedCursorSequence() {
        synchronized (FORCED_CURSOR_LOCK) {
            return forcedCursorSequence;
        }
    }

    static Integer currentForcedCursorType() {
        return forcedCursorType;
    }

    static void resetForcedCursorState() {
        synchronized (FORCED_CURSOR_LOCK) {
            forcedCursorSequence = 0;
            forcedCursorType = null;
        }
    }

    static Integer toAwtCursorType(String cssCursor) {
        if (cssCursor == null) {
            return null;
        }
        return switch (cssCursor) {
            case "ns-resize" -> Cursor.N_RESIZE_CURSOR;
            case "ew-resize" -> Cursor.E_RESIZE_CURSOR;
            default -> null;
        };
    }

    private static void applyCursor(CefBrowser browser, int cursorType) {
        if (browser == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            int effectiveCursorType = effectiveCursorType(cursorType);
            if (!isPredefinedCursorType(effectiveCursorType)) {
                return;
            }
            Cursor awtCursor = Cursor.getPredefinedCursor(effectiveCursorType);
            Component component = browser.getUIComponent();
            while (component != null) {
                component.setCursor(awtCursor);
                component = component.getParent();
            }
        });
    }

    static int effectiveCursorType(int cursorType) {
        Integer forcedType = forcedCursorType;
        return forcedType == null ? cursorType : forcedType;
    }

    static boolean isPredefinedCursorType(int cursorType) {
        return cursorType >= Cursor.DEFAULT_CURSOR && cursorType <= Cursor.MOVE_CURSOR;
    }
}
