package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.update.Updater;
import ai.chat2db.community.tools.console.ConsoleResult;
import ai.chat2db.community.tools.util.ConfigUtils;
import com.alibaba.fastjson2.JSON;
import org.cef.browser.CefBrowser;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ApplicationExitCoordinator {

    public enum ExitAction {
        CLOSE,
        RESTART,
        INSTALL_UPDATE
    }

    private static final AtomicReference<Runnable> PENDING_EXIT = new AtomicReference<>();

    private ApplicationExitCoordinator() {
    }

    public static void request(String action) {
        request(action, () -> execute(action));
    }

    public static void request(String action, Runnable confirmedAction) {
        ExitAction validatedAction = requireAction(action);
        Objects.requireNonNull(confirmedAction, "Confirmed exit action is required");
        if (!ConfigUtils.isCommunity()) {
            confirmedAction.run();
            return;
        }
        CefBrowser browser = JcefContext.getInstance().getBrowser_();
        if (browser == null) {
            confirmedAction.run();
            return;
        }
        PENDING_EXIT.set(confirmedAction);
        ConsoleResult result = ConsoleResult.builder()
                .actionType(ActionTypeEnum.APP_EXIT_REQUESTED.getName())
                .message(Map.of("reason", validatedAction.name()))
                .build();
        CallJsFunctionUtil.callHandleJavaMessage(browser, JSON.toJSONString(result));
    }

    public static boolean confirm() {
        Runnable confirmedAction = PENDING_EXIT.getAndSet(null);
        if (confirmedAction == null) {
            return false;
        }
        confirmedAction.run();
        return true;
    }

    private static void execute(String action) {
        switch (requireAction(action)) {
            case CLOSE -> OSOperateUtil.closeWindows(JcefContext.getInstance().getFrame_());
            case RESTART -> {
                try {
                    Updater.getInstance().restartAppNow();
                } catch (IOException e) {
                    throw new IllegalStateException("Could not restart the application", e);
                }
            }
            case INSTALL_UPDATE -> Updater.getInstance().triggerInstallationWithAuxiliaryProcessNow();
        }
    }

    private static ExitAction requireAction(String action) {
        if (action == null) {
            throw new IllegalArgumentException("Exit action is required");
        }
        try {
            return ExitAction.valueOf(action);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported exit action: " + action, e);
        }
    }
}
