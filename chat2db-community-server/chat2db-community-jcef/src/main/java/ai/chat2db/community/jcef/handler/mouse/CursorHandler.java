package ai.chat2db.community.jcef.handler.mouse;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicLong;

public class CursorHandler extends CefDisplayHandlerAdapter {

    private static volatile Integer forcedCursorType;
    private static final AtomicLong forcedCursorSequence = new AtomicLong();

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        Integer forcedType = forcedCursorType;
        int effectiveCursorType = forcedType == null ? cursorType : forcedType;
        if (!isPredefinedCursorType(effectiveCursorType)) {
            return false;
        }
        applyCursor(browser, effectiveCursorType);
        return true;
    }

    public static void setForcedCursor(CefBrowser browser, String cssCursor, long sequence) {
        long currentSequence;
        do {
            currentSequence = forcedCursorSequence.get();
            if (sequence <= currentSequence) {
                return;
            }
        } while (!forcedCursorSequence.compareAndSet(currentSequence, sequence));
        Integer cursorType = toAwtCursorType(cssCursor);
        forcedCursorType = cursorType;
        applyCursor(browser, cursorType == null ? Cursor.DEFAULT_CURSOR : cursorType);
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
        Cursor awtCursor = Cursor.getPredefinedCursor(cursorType);
        SwingUtilities.invokeLater(() -> {
            Component component = browser.getUIComponent();
            while (component != null) {
                component.setCursor(awtCursor);
                component = component.getParent();
            }
        });
    }

    static boolean isPredefinedCursorType(int cursorType) {
        return cursorType >= Cursor.DEFAULT_CURSOR && cursorType <= Cursor.MOVE_CURSOR;
    }
}
