package ai.chat2db.community.jcef.utils;

import org.junit.jupiter.api.Test;

import java.awt.Frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OSOperateUtilWindowStateTest {

    @Test
    void recognizesCombinedMaximizedStateBits() {
        assertFalse(OSOperateUtil.isMaximizedState(Frame.NORMAL));
        assertFalse(OSOperateUtil.isMaximizedState(Frame.MAXIMIZED_HORIZ));
        assertTrue(OSOperateUtil.isMaximizedState(Frame.MAXIMIZED_BOTH));
        assertTrue(OSOperateUtil.isMaximizedState(Frame.MAXIMIZED_BOTH | Frame.ICONIFIED));
    }

    @Test
    void togglesMaximizedBitsWithoutChangingOtherStateBits() {
        assertEquals(Frame.MAXIMIZED_BOTH, OSOperateUtil.toggleMaximizedState(Frame.NORMAL));
        assertEquals(Frame.NORMAL, OSOperateUtil.toggleMaximizedState(Frame.MAXIMIZED_BOTH));
        assertEquals(
                Frame.MAXIMIZED_BOTH,
                OSOperateUtil.toggleMaximizedState(Frame.MAXIMIZED_BOTH | Frame.ICONIFIED)
        );
    }
}
