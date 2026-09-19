package ai.chat2db.community.jcef.handler.mouse;

import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Panel;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorHandlerTest {

    @BeforeEach
    void resetForcedCursorState() {
        CursorHandler.resetForcedCursorState();
    }

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

    @Test
    void ignoresOlderForcedCursorUpdates() {
        assertTrue(CursorHandler.updateForcedCursor("ns-resize", 20));
        assertFalse(CursorHandler.updateForcedCursor("ew-resize", 19));

        assertEquals(20, CursorHandler.currentForcedCursorSequence());
        assertEquals(Cursor.N_RESIZE_CURSOR, CursorHandler.currentForcedCursorType());
        assertEquals(Cursor.N_RESIZE_CURSOR, CursorHandler.effectiveCursorType(Cursor.DEFAULT_CURSOR));

        assertTrue(CursorHandler.updateForcedCursor("default", 21));
        assertNull(CursorHandler.currentForcedCursorType());
        assertEquals(Cursor.DEFAULT_CURSOR, CursorHandler.effectiveCursorType(Cursor.DEFAULT_CURSOR));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void queuedSwingUpdateUsesLatestForcedCursor() throws Exception {
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        Component component = new Panel();
        CefBrowser browser = browserWithUiComponent(component);
        try {
            assertTrue(edtBlocked.await(5, TimeUnit.SECONDS));
            assertTrue(CursorHandler.updateForcedCursor("ns-resize", 20));
            assertTrue(new CursorHandler().onCursorChange(browser, Cursor.HAND_CURSOR));
            assertTrue(CursorHandler.updateForcedCursor("ew-resize", 21));
        } finally {
            releaseEdt.countDown();
        }
        SwingUtilities.invokeAndWait(() -> {
        });

        assertEquals(Cursor.E_RESIZE_CURSOR, component.getCursor().getType());
    }

    @Test
    void windowsLeavesBrowserAndResizeCursorsToChromium() throws Exception {
        String executable = org.cef.OS.isWindows() ? "java.exe" : "java";
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Djava.awt.headless=true",
                "-Dos.name=Windows 11",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                WindowsCursorProbe.class.getName()
        ).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Windows cursor probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    public static class WindowsCursorProbe {
        public static void main(String[] args) throws Exception {
            assertTrue(org.cef.OS.isWindows());
            assertFalse(CursorHandler.isNativeCursorOverrideEnabled());
            Component component = new Panel();
            CefBrowser browser = (CefBrowser) Proxy.newProxyInstance(
                    CefBrowser.class.getClassLoader(), new Class<?>[]{CefBrowser.class},
                    (proxy, method, arguments) -> method.getName().equals("getUIComponent") ? component : null);
            CursorHandler handler = new CursorHandler();
            for (int type = Cursor.DEFAULT_CURSOR; type <= Cursor.MOVE_CURSOR; type++) {
                assertFalse(handler.onCursorChange(browser, type));
            }
            SwingUtilities.invokeAndWait(() -> {
            });
            assertEquals(Cursor.MOVE_CURSOR, component.getCursor().getType());
            CursorHandler.setForcedCursor(browser, "ns-resize", 1);
            CursorHandler.setForcedCursor(browser, "ew-resize", 2);
            CursorHandler.setForcedCursor(browser, "default", 3);
            assertNull(CursorHandler.currentForcedCursorType());
            assertEquals(0, CursorHandler.currentForcedCursorSequence());
        }
    }

    @Test
    void highestConcurrentSequenceOwnsForcedCursor() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> updates = LongStream.rangeClosed(1, 200)
                    .mapToObj(sequence -> (Callable<Boolean>) () -> CursorHandler.updateForcedCursor(
                            sequence == 200 ? "ew-resize" : "ns-resize",
                            sequence
                    ))
                    .toList();
            for (Future<Boolean> update : executor.invokeAll(updates)) {
                update.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(200, CursorHandler.currentForcedCursorSequence());
        assertEquals(Cursor.E_RESIZE_CURSOR, CursorHandler.currentForcedCursorType());
    }

    private CefBrowser browserWithUiComponent(Component component) {
        return (CefBrowser) Proxy.newProxyInstance(
                CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class},
                (proxy, method, args) -> method.getName().equals("getUIComponent") ? component : null
        );
    }
}
