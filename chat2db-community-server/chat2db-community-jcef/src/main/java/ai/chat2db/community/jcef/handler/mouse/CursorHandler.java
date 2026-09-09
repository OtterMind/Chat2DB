package ai.chat2db.community.jcef.handler.mouse;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

public class CursorHandler extends CefDisplayHandlerAdapter {

    private static final Object FORCED_CURSOR_LOCK = new Object();
    private static final Map<Component, CursorState> FORCED_CURSOR_RESTORE_VALUES = new IdentityHashMap<>();
    private static final Map<Component, Integer> BROWSER_CURSOR_TYPES = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Integer forcedCursorType;
    private static long forcedCursorSequence;

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        int effectiveCursorType = effectiveCursorType(cursorType);
        if (!isPredefinedCursorType(effectiveCursorType)) {
            return false;
        }
        applyCursor(browser, isPredefinedCursorType(cursorType) ? cursorType : null);
        return true;
    }

    public static void setForcedCursor(CefBrowser browser, String cssCursor, long sequence) {
        if (!updateForcedCursor(cssCursor, sequence)) {
            return;
        }
        applyCursor(browser, null);
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
            FORCED_CURSOR_RESTORE_VALUES.clear();
            BROWSER_CURSOR_TYPES.clear();
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

    private static void applyCursor(CefBrowser browser, Integer browserCursorType) {
        if (browser == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            Component browserComponent = browser.getUIComponent();
            if (browserComponent == null) {
                return;
            }
            if (browserCursorType != null) {
                BROWSER_CURSOR_TYPES.put(browserComponent, browserCursorType);
            }

            Integer forcedType = forcedCursorType;
            if (forcedType != null) {
                applyForcedCursor(browserComponent, forcedType);
                return;
            }

            restoreForcedCursorComponents();
            int cursorType = BROWSER_CURSOR_TYPES.getOrDefault(browserComponent, Cursor.DEFAULT_CURSOR);
            browserComponent.setCursor(Cursor.getPredefinedCursor(cursorType));
        });
    }

    private static void applyForcedCursor(Component browserComponent, int cursorType) {
        Cursor cursor = Cursor.getPredefinedCursor(cursorType);
        Component component = browserComponent;
        while (component != null) {
            FORCED_CURSOR_RESTORE_VALUES.putIfAbsent(
                    component,
                    new CursorState(component.isCursorSet(), component.getCursor())
            );
            component.setCursor(cursor);
            component = component.getParent();
        }
    }

    private static void restoreForcedCursorComponents() {
        FORCED_CURSOR_RESTORE_VALUES.forEach((component, state) ->
                component.setCursor(state.explicitlySet() ? state.cursor() : null));
        FORCED_CURSOR_RESTORE_VALUES.clear();
    }

    static int effectiveCursorType(int cursorType) {
        Integer forcedType = forcedCursorType;
        return forcedType == null ? cursorType : forcedType;
    }

    static boolean isPredefinedCursorType(int cursorType) {
        return cursorType >= Cursor.DEFAULT_CURSOR && cursorType <= Cursor.MOVE_CURSOR;
    }

    private record CursorState(boolean explicitlySet, Cursor cursor) {
    }
}
