package ai.chat2db.community.jcef.handler.mouse;

import org.junit.jupiter.api.Test;

import java.awt.Cursor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorHandlerTest {

    @Test
    void supportsEveryPredefinedAwtCursorUsedByJcef() {
        for (int cursorType = Cursor.DEFAULT_CURSOR; cursorType <= Cursor.MOVE_CURSOR; cursorType++) {
            assertTrue(CursorHandler.isPredefinedCursorType(cursorType));
        }
        assertFalse(CursorHandler.isPredefinedCursorType(-1));
        assertFalse(CursorHandler.isPredefinedCursorType(Cursor.MOVE_CURSOR + 1));
    }

    @Test
    void mapsWorkspaceResizeCssCursors() {
        assertEquals(Cursor.N_RESIZE_CURSOR, CursorHandler.toAwtCursorType("ns-resize"));
        assertEquals(Cursor.E_RESIZE_CURSOR, CursorHandler.toAwtCursorType("ew-resize"));
        assertNull(CursorHandler.toAwtCursorType("default"));
    }
}
