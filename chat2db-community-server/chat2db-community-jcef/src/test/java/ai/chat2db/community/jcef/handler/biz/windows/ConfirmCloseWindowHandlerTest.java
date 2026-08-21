package ai.chat2db.community.jcef.handler.biz.windows;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.utils.ApplicationExitCoordinator;
import ai.chat2db.community.tools.console.ConsoleMessage;
import ai.chat2db.community.tools.console.ConsoleResult;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmCloseWindowHandlerTest {

    private CefBrowser originalBrowser;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        originalBrowser = JcefContext.getInstance().getBrowser_();
        System.setProperty("chat2db.runtime.mode", "community");
        System.setProperty("chat2db.mode", "DESKTOP");
    }

    @AfterEach
    void tearDown() throws ReflectiveOperationException {
        ApplicationExitCoordinator.cancel("install-test");
        setBrowser(originalBrowser);
        System.clearProperty("chat2db.runtime.mode");
        System.clearProperty("chat2db.mode");
    }

    @Test
    void acceptedResultAndCommandResponsePrecedeDelayedExitScheduling() throws ReflectiveOperationException {
        List<String> order = new ArrayList<>();
        setBrowser(browserProxy(order));
        assertTrue(ApplicationExitCoordinator.request("INSTALL_UPDATE", "install-test", () -> true));
        order.clear();

        ConsoleMessage message = new ConsoleMessage();
        message.setMessage("{\"operationId\":\"install-test\"}");
        CefQueryCallback callback = new CefQueryCallback() {
            @Override
            public void success(String response) {
                assertTrue(response.contains("true"));
                order.add("response");
            }

            @Override
            public void failure(int errorCode, String errorMessage) {
                throw new AssertionError(errorMessage);
            }
        };

        new ConfirmCloseWindowHandler(() -> order.add("exit-scheduled"))
                .handle(message, new ConsoleResult(), callback);

        assertEquals(List.of("accepted-event", "response", "exit-scheduled"), order);
    }

    private void setBrowser(CefBrowser browser) throws ReflectiveOperationException {
        Field field = JcefContext.class.getDeclaredField("browser_");
        field.setAccessible(true);
        field.set(JcefContext.getInstance(), browser);
    }

    private CefBrowser browserProxy(List<String> order) {
        return (CefBrowser) Proxy.newProxyInstance(
                CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("executeJavaScript")) {
                        String script = (String) args[0];
                        if (script.contains("ACCEPTED")) {
                            order.add("accepted-event");
                        }
                        return null;
                    }
                    if (method.getName().equals("getURL")) {
                        return "about:blank";
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(int.class)) {
                        return 0;
                    }
                    if (method.getReturnType().equals(long.class)) {
                        return 0L;
                    }
                    return null;
                }
        );
    }
}
