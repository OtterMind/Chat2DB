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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class ApplicationExitCoordinator {

    public enum ExitAction {
        CLOSE,
        RESTART,
        INSTALL_UPDATE
    }

    private static final AtomicReference<PendingExit> PENDING_EXIT = new AtomicReference<>();

    private ApplicationExitCoordinator() {
    }

    public static boolean request(String action) {
        return request(action, UUID.randomUUID().toString(), () -> {
            execute(action);
            return true;
        });
    }

    public static boolean request(String action, String operationId, BooleanSupplier confirmedAction) {
        ExitAction validatedAction = requireAction(action);
        String validatedOperationId = requireOperationId(operationId);
        Objects.requireNonNull(confirmedAction, "Confirmed exit action is required");
        if (!ConfigUtils.isCommunity()) {
            return confirmedAction.getAsBoolean();
        }
        CefBrowser browser = JcefContext.getInstance().getBrowser_();
        if (browser == null) {
            return confirmedAction.getAsBoolean();
        }
        PendingExit pendingExit = new PendingExit(validatedOperationId, confirmedAction);
        if (!PENDING_EXIT.compareAndSet(null, pendingExit)) {
            return false;
        }
        ConsoleResult result = ConsoleResult.builder()
                .actionType(ActionTypeEnum.APP_EXIT_REQUESTED.getName())
                .message(Map.of(
                        "reason", validatedAction.name(),
                        "operationId", validatedOperationId
                ))
                .build();
        try {
            CallJsFunctionUtil.callHandleJavaMessage(browser, JSON.toJSONString(result));
        } catch (RuntimeException exception) {
            PENDING_EXIT.compareAndSet(pendingExit, null);
            throw exception;
        }
        return true;
    }

    public static boolean confirm(String operationId) {
        PendingExit pendingExit = takePendingExit(operationId);
        if (pendingExit == null) {
            return false;
        }
        return pendingExit.confirmedAction().getAsBoolean();
    }

    public static boolean cancel(String operationId) {
        return takePendingExit(operationId) != null;
    }

    private static PendingExit takePendingExit(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return null;
        }
        while (true) {
            PendingExit pendingExit = PENDING_EXIT.get();
            if (pendingExit == null || !pendingExit.operationId().equals(operationId)) {
                return null;
            }
            if (PENDING_EXIT.compareAndSet(pendingExit, null)) {
                return pendingExit;
            }
        }
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

    private static String requireOperationId(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("Exit operation ID is required");
        }
        return operationId;
    }

    private record PendingExit(String operationId, BooleanSupplier confirmedAction) {
    }
}
