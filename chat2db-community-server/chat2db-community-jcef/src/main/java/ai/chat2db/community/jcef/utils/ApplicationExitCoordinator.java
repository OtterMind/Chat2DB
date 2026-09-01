package ai.chat2db.community.jcef.utils;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.ActionTypeEnum;
import ai.chat2db.community.jcef.update.Updater;
import ai.chat2db.community.tools.console.ConsoleResult;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.cef.browser.CefBrowser;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@Slf4j
public final class ApplicationExitCoordinator {

    private static final long FRONTEND_ACK_TIMEOUT_MILLIS = 3000L;

    private static final ScheduledExecutorService ACK_TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chat2db-application-exit-ack-timeout");
                thread.setDaemon(true);
                return thread;
            });

    public enum ExitAction {
        CLOSE,
        RESTART,
        INSTALL_UPDATE
    }

    private static final AtomicReference<PendingExit> PENDING_EXIT = new AtomicReference<>();
    private static final AtomicBoolean FRONTEND_READY = new AtomicBoolean();

    private ApplicationExitCoordinator() {
    }

    public static boolean request(String action) {
        return request(action, UUID.randomUUID().toString(), () -> {
            execute(action);
            return true;
        });
    }

    public static boolean request(String action, String operationId, BooleanSupplier confirmedAction) {
        return request(action, operationId, confirmedAction, FRONTEND_ACK_TIMEOUT_MILLIS);
    }

    static boolean request(String action, String operationId, BooleanSupplier confirmedAction,
            long acknowledgementTimeoutMillis) {
        ExitAction validatedAction = requireAction(action);
        String validatedOperationId = requireOperationId(operationId);
        Objects.requireNonNull(confirmedAction, "Confirmed exit action is required");
        if (!FRONTEND_READY.get()) {
            return confirmedAction.getAsBoolean();
        }
        CefBrowser browser = JcefContext.getInstance().getBrowser_();
        if (browser == null) {
            return confirmedAction.getAsBoolean();
        }
        PendingExit pendingExit = new PendingExit(validatedOperationId, confirmedAction, new AtomicBoolean());
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
            FRONTEND_READY.set(false);
            return confirmedAction.getAsBoolean();
        }
        ACK_TIMEOUT_EXECUTOR.schedule(() -> fallbackIfUnacknowledged(pendingExit),
                Math.max(1L, acknowledgementTimeoutMillis), TimeUnit.MILLISECONDS);
        return true;
    }

    public static boolean acknowledge(String operationId) {
        PendingExit pendingExit = PENDING_EXIT.get();
        if (pendingExit == null || operationId == null || !operationId.equals(pendingExit.operationId())) {
            return false;
        }
        pendingExit.acknowledged().set(true);
        return PENDING_EXIT.get() == pendingExit;
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

    public static void markFrontendReady() {
        FRONTEND_READY.set(true);
    }

    public static boolean isFrontendReady() {
        return FRONTEND_READY.get();
    }

    public static void markFrontendUnavailable() {
        FRONTEND_READY.set(false);
    }

    private static void fallbackIfUnacknowledged(PendingExit pendingExit) {
        if (pendingExit.acknowledged().get() || !PENDING_EXIT.compareAndSet(pendingExit, null)) {
            return;
        }
        FRONTEND_READY.set(false);
        try {
            pendingExit.confirmedAction().getAsBoolean();
        } catch (RuntimeException exception) {
            log.error("Application exit fallback failed for operation {}", pendingExit.operationId(), exception);
        }
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

    private record PendingExit(String operationId, BooleanSupplier confirmedAction, AtomicBoolean acknowledged) {
    }
}
