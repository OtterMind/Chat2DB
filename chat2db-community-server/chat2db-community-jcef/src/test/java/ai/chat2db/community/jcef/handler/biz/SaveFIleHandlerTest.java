package ai.chat2db.community.jcef.handler.biz;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.handler.CefDialogHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveFIleHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldUseJcefSaveDialogAndSaveSelectedFile() throws Exception {
        SaveFIleHandler handler = new SaveFIleHandler();
        Path selectedFile = tempDir.resolve("connections.json");
        AtomicReference<CefDialogHandler.FileDialogMode> mode = new AtomicReference<>();
        AtomicReference<String> title = new AtomicReference<>();
        AtomicReference<String> defaultFileName = new AtomicReference<>();
        AtomicReference<Vector<String>> filters = new AtomicReference<>();
        AtomicReference<String> successResponse = new AtomicReference<>();
        AtomicInteger failureCount = new AtomicInteger();

        CefBrowser browser = browserProxy((methodArgs) -> {
            mode.set((CefDialogHandler.FileDialogMode) methodArgs[0]);
            title.set((String) methodArgs[1]);
            defaultFileName.set((String) methodArgs[2]);
            filters.set(castFilters(methodArgs[3]));
            CefRunFileDialogCallback dialogCallback = (CefRunFileDialogCallback) methodArgs[4];
            dialogCallback.onFileDialogDismissed(new Vector<>(java.util.List.of(selectedFile.toString())));
        });

        boolean opened = handler.openByJcefSaveDialog(
                browser,
                "Save File",
                "export_chat2db_connections.json",
                "{\"connections\":[]}",
                "json",
                queryCallback(successResponse, failureCount)
        );

        assertTrue(opened);
        assertEquals(CefDialogHandler.FileDialogMode.FILE_DIALOG_SAVE, mode.get());
        assertEquals("Save File", title.get());
        assertEquals("export_chat2db_connections.json", defaultFileName.get());
        assertEquals(new Vector<>(java.util.List.of(".json")), filters.get());
        assertEquals("{\"connections\":[]}", Files.readString(selectedFile));
        assertTrue(successResponse.get().contains("\"path\""));
        assertEquals(0, failureCount.get());
    }

    @Test
    void shouldReturnEmptyDataWhenSaveDialogIsCancelled() {
        SaveFIleHandler handler = new SaveFIleHandler();
        AtomicReference<String> successResponse = new AtomicReference<>();
        AtomicInteger failureCount = new AtomicInteger();
        CefBrowser browser = browserProxy((methodArgs) -> {
            CefRunFileDialogCallback dialogCallback = (CefRunFileDialogCallback) methodArgs[4];
            dialogCallback.onFileDialogDismissed(new Vector<>());
        });

        boolean opened = handler.openByJcefSaveDialog(
                browser,
                "Save File",
                "connections.json",
                "{}",
                "json",
                queryCallback(successResponse, failureCount)
        );

        assertTrue(opened);
        assertEquals("{\"data\":null}", successResponse.get());
        assertEquals(0, failureCount.get());
    }

    @Test
    void shouldFallBackWhenJcefSaveDialogCannotOpen() {
        SaveFIleHandler handler = new SaveFIleHandler();
        CefBrowser browser = browserProxy((methodArgs) -> {
            throw new IllegalStateException("dialog unavailable");
        });

        boolean opened = handler.openByJcefSaveDialog(
                browser,
                "Save File",
                "connections.json",
                "{}",
                "json",
                queryCallback(new AtomicReference<>(), new AtomicInteger())
        );

        assertEquals(false, opened);
    }

    @SuppressWarnings("unchecked")
    private Vector<String> castFilters(Object value) {
        return (Vector<String>) value;
    }

    private CefBrowser browserProxy(DialogInvocation invocation) {
        return (CefBrowser) Proxy.newProxyInstance(
                CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("runFileDialog")) {
                        invocation.invoke(args);
                        return null;
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(int.class)) {
                        return 0;
                    }
                    if (method.getReturnType().equals(double.class)) {
                        return 0D;
                    }
                    return null;
                }
        );
    }

    private CefQueryCallback queryCallback(AtomicReference<String> successResponse, AtomicInteger failureCount) {
        return new CefQueryCallback() {
            @Override
            public void success(String response) {
                successResponse.set(response);
            }

            @Override
            public void failure(int errorCode, String errorMessage) {
                failureCount.incrementAndGet();
            }
        };
    }

    @FunctionalInterface
    private interface DialogInvocation {
        void invoke(Object[] args);
    }
}
