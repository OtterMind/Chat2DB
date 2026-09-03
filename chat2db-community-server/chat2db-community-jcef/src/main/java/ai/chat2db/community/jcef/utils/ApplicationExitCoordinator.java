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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    private static final Object FRONTEND_STATE_LOCK = new Object();
    private static volatile boolean frontendReady;

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
        return request(action, operationId, confirmedAction, acknowledgementTimeoutMillis,
                (task, delayMillis) -> ACK_TIMEOUT_EXECUTOR.schedule(task, delayMillis, TimeUnit.MILLISECONDS));
    }

    static boolean request(String action, String operationId, BooleanSupplier confirmedAction,
            long acknowledgementTimeoutMillis, AcknowledgementTimeoutScheduler timeoutScheduler) {
        ExitAction validatedAction = requireAction(action);
        String validatedOperationId = requireOperationId(operationId);
        Objects.requireNonNull(confirmedAction, "Confirmed exit action is required");
        Objects.requireNonNull(timeoutScheduler, "Acknowledgement timeout scheduler is required");
        CefBrowser browser;
        PendingExit pendingExit = new PendingExit(validatedOperationId, confirmedAction);
        synchronized (FRONTEND_STATE_LOCK) {
            browser = frontendReady ? JcefContext.getInstance().getBrowser_() : null;
            if (!PENDING_EXIT.compareAndSet(null, pendingExit)) {
                return false;
            }
        }
        PendingExit dispatchedExit = pendingExit;
        if (browser == null) {
            PendingExit claimedExit = claimPendingExit(dispatchedExit, false);
            if (claimedExit == null) {
                return true;
            }
            try {
                return claimedExit.confirmedAction().getAsBoolean();
            } finally {
                releasePendingExit(claimedExit);
            }
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
            PendingExit claimedExit;
            synchronized (FRONTEND_STATE_LOCK) {
                claimedExit = claimPendingExit(dispatchedExit, false);
                if (claimedExit != null) {
                    frontendReady = false;
                }
            }
            return claimedExit == null || runFallback(claimedExit);
        }
        try {
            ScheduledFuture<?> timeoutFuture = timeoutScheduler.schedule(
                    () -> fallbackIfUnacknowledged(dispatchedExit),
                    Math.max(1L, acknowledgementTimeoutMillis));
            dispatchedExit.attachAcknowledgementTimeout(timeoutFuture);
        } catch (RuntimeException exception) {
            log.error("Could not schedule application exit acknowledgement timeout for operation {}",
                    dispatchedExit.operationId(), exception);
            PendingExit claimedExit = claimPendingExit(dispatchedExit, false);
            return claimedExit == null || runFallback(claimedExit);
        }
        return true;
    }

    public static boolean acknowledge(String operationId) {
        PendingExit pendingExit = PENDING_EXIT.get();
        if (pendingExit == null || operationId == null || !operationId.equals(pendingExit.operationId())) {
            return false;
        }
        if (!pendingExit.acknowledge()) {
            return false;
        }
        return PENDING_EXIT.get() == pendingExit;
    }

    public static boolean confirm(String operationId) {
        PendingExit pendingExit = takePendingExit(operationId);
        if (pendingExit == null) {
            return false;
        }
        try {
            return pendingExit.confirmedAction().getAsBoolean();
        } finally {
            releasePendingExit(pendingExit);
        }
    }

    public static boolean cancel(String operationId) {
        PendingExit pendingExit = takePendingExit(operationId);
        if (pendingExit == null) {
            return false;
        }
        releasePendingExit(pendingExit);
        return true;
    }

    public static void markFrontendReady() {
        synchronized (FRONTEND_STATE_LOCK) {
            frontendReady = true;
        }
    }

    public static boolean isFrontendReady() {
        return frontendReady;
    }

    public static void markFrontendUnavailable() {
        PendingExit pendingExit;
        synchronized (FRONTEND_STATE_LOCK) {
            frontendReady = false;
            PendingExit currentExit = PENDING_EXIT.get();
            pendingExit = currentExit == null ? null : claimPendingExit(currentExit, false);
        }
        if (pendingExit != null) {
            runFallback(pendingExit);
        }
    }

    private static void fallbackIfUnacknowledged(PendingExit pendingExit) {
        PendingExit claimedExit;
        synchronized (FRONTEND_STATE_LOCK) {
            claimedExit = claimPendingExit(pendingExit, true);
            if (claimedExit != null) {
                frontendReady = false;
            }
        }
        if (claimedExit != null) {
            runFallback(claimedExit);
        }
    }

    private static boolean runFallback(PendingExit pendingExit) {
        try {
            return pendingExit.confirmedAction().getAsBoolean();
        } catch (RuntimeException exception) {
            log.error("Application exit fallback failed for operation {}", pendingExit.operationId(), exception);
            return false;
        } finally {
            releasePendingExit(pendingExit);
        }
    }

    private static PendingExit takePendingExit(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return null;
        }
        PendingExit pendingExit = PENDING_EXIT.get();
        if (pendingExit == null || !pendingExit.operationId().equals(operationId)) {
            return null;
        }
        return claimPendingExit(pendingExit, false);
    }

    private static PendingExit claimPendingExit(PendingExit pendingExit, boolean onlyIfUnacknowledged) {
        boolean claimed = onlyIfUnacknowledged
                ? pendingExit.claimIfUnacknowledged()
                : pendingExit.claim();
        if (!claimed || PENDING_EXIT.get() != pendingExit) {
            return null;
        }
        pendingExit.cancelAcknowledgementTimeout();
        return pendingExit;
    }

    private static void releasePendingExit(PendingExit pendingExit) {
        pendingExit.cancelAcknowledgementTimeout();
        PENDING_EXIT.compareAndSet(pendingExit, null);
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

    private enum PendingExitState {
        AWAITING_ACKNOWLEDGEMENT,
        ACKNOWLEDGED,
        CLAIMED
    }

    private static final class PendingExit {
        private final String operationId;
        private final BooleanSupplier confirmedAction;
        private final AtomicReference<PendingExitState> state =
                new AtomicReference<>(PendingExitState.AWAITING_ACKNOWLEDGEMENT);
        private final AtomicReference<ScheduledFuture<?>> acknowledgementTimeout = new AtomicReference<>();

        private PendingExit(String operationId, BooleanSupplier confirmedAction) {
            this.operationId = operationId;
            this.confirmedAction = confirmedAction;
        }

        private String operationId() {
            return operationId;
        }

        private BooleanSupplier confirmedAction() {
            return confirmedAction;
        }

        private boolean acknowledge() {
            if (!state.compareAndSet(PendingExitState.AWAITING_ACKNOWLEDGEMENT,
                    PendingExitState.ACKNOWLEDGED)) {
                return false;
            }
            cancelAcknowledgementTimeout();
            return true;
        }

        private boolean claimIfUnacknowledged() {
            return state.compareAndSet(PendingExitState.AWAITING_ACKNOWLEDGEMENT,
                    PendingExitState.CLAIMED);
        }

        private boolean claim() {
            while (true) {
                PendingExitState currentState = state.get();
                if (currentState == PendingExitState.CLAIMED) {
                    return false;
                }
                if (state.compareAndSet(currentState, PendingExitState.CLAIMED)) {
                    return true;
                }
            }
        }

        private void attachAcknowledgementTimeout(ScheduledFuture<?> timeoutFuture) {
            Objects.requireNonNull(timeoutFuture, "Acknowledgement timeout future is required");
            if (!acknowledgementTimeout.compareAndSet(null, timeoutFuture)) {
                throw new IllegalStateException("Acknowledgement timeout is already attached");
            }
            if (state.get() != PendingExitState.AWAITING_ACKNOWLEDGEMENT) {
                cancelAcknowledgementTimeout();
            }
        }

        private void cancelAcknowledgementTimeout() {
            ScheduledFuture<?> timeoutFuture = acknowledgementTimeout.getAndSet(null);
            if (timeoutFuture != null) {
                try {
                    timeoutFuture.cancel(false);
                } catch (RuntimeException exception) {
                    log.warn("Could not cancel application exit acknowledgement timeout for operation {}",
                            operationId, exception);
                }
            }
        }
    }

    @FunctionalInterface
    interface AcknowledgementTimeoutScheduler {
        ScheduledFuture<?> schedule(Runnable task, long delayMillis);
    }
}
