package ai.chat2db.community.jcef.frame;

import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainJFrameTest {

    @Test
    void shouldCreateBrowserImmediatelyForHiddenStartup() {
        AtomicInteger immediateCreationCount = new AtomicInteger();
        CefBrowser browser = recordingBrowser(immediateCreationCount);

        MainJFrame.createBrowserImmediatelyForHiddenStartup(browser, false);

        assertEquals(1, immediateCreationCount.get());
    }

    @Test
    void shouldKeepLazyBrowserCreationForVisibleStartup() {
        AtomicInteger immediateCreationCount = new AtomicInteger();
        CefBrowser browser = recordingBrowser(immediateCreationCount);

        MainJFrame.createBrowserImmediatelyForHiddenStartup(browser, true);

        assertEquals(0, immediateCreationCount.get());
    }

    private static CefBrowser recordingBrowser(AtomicInteger immediateCreationCount) {
        return (CefBrowser) Proxy.newProxyInstance(
                CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class},
                (proxy, method, args) -> {
                    if ("createImmediately".equals(method.getName())) {
                        immediateCreationCount.incrementAndGet();
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
