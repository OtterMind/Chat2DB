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
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class ApplicationExitCoordinator {

    static final long PENDING_EXIT_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final ScheduledThreadPoolExecutor TIMEOUT_EXECUTOR = createTimeoutExecutor();

    public enum ExitAction {
        CLOSE,
        RESTART,
        INSTALL_UPDATE
    }

    public enum ExitResult {
        ACCEPTED,
        CANCELLED,
        FAILED
    }

    private static final AtomicReference<PendingExit> PENDING_EXIT = new AtomicReference<>();
    private static volatile LongSupplier nanoClock = System::nanoTime;
    private static volatile TimeoutScheduler timeoutScheduler = (task, delay, unit) -> {
        var future = TIMEOUT_EXECUTOR.schedule(task, delay, unit);
        return () -> future.cancel(false);
    };

    private ApplicationExitCoordinator() {
    }

    public static boolean request(String action) {
        return request(action, UUID.randomUUID().toString(), () -> {
            return execute(action);
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
        PendingExit pendingExit = new PendingExit(validatedAction, validatedOperationId, confirmedAction,
                nanoClock.getAsLong());
        while (true) {
            PendingExit existing = PENDING_EXIT.get();
            if (existing == null) {
                if (PENDING_EXIT.compareAndSet(null, pendingExit)) {
                    break;
                }
                continue;
            }
            if (!isExpired(existing, nanoClock.getAsLong())) {
                return false;
            }
            if (PENDING_EXIT.compareAndSet(existing, null)) {
                cancelTimeout(existing);
                publishResult(existing, ExitResult.FAILED);
            }
        }
        try {
            TimeoutHandle timeoutHandle = Objects.requireNonNull(timeoutScheduler.schedule(
                    () -> expirePending(pendingExit), PENDING_EXIT_TIMEOUT_NANOS, TimeUnit.NANOSECONDS),
                    "Timeout scheduler returned no handle");
            pendingExit.timeoutHandle().set(timeoutHandle);
            if (PENDING_EXIT.get() != pendingExit) {
                cancelTimeout(pendingExit);
            }
        } catch (RuntimeException exception) {
            PENDING_EXIT.compareAndSet(pendingExit, null);
            throw exception;
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
            if (PENDING_EXIT.compareAndSet(pendingExit, null)) {
                cancelTimeout(pendingExit);
            }
            throw exception;
        }
        return true;
    }

    public static boolean confirm(String operationId) {
        return confirmPending(operationId).accepted();
    }

    public static Confirmation confirmPending(String operationId) {
        PendingExit pendingExit = takePendingExit(operationId);
        if (pendingExit == null) {
            return Confirmation.rejected();
        }
        try {
            boolean accepted = pendingExit.confirmedAction().getAsBoolean();
            publishResult(pendingExit, accepted ? ExitResult.ACCEPTED : ExitResult.FAILED);
            return new Confirmation(accepted, pendingExit.action());
        } catch (RuntimeException exception) {
            publishResult(pendingExit, ExitResult.FAILED);
            return new Confirmation(false, pendingExit.action());
        }
    }

    public static boolean cancel(String operationId) {
        PendingExit pendingExit = takePendingExit(operationId);
        if (pendingExit == null) {
            return false;
        }
        publishResult(pendingExit, ExitResult.CANCELLED);
        return true;
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
            if (isExpired(pendingExit, nanoClock.getAsLong())) {
                if (PENDING_EXIT.compareAndSet(pendingExit, null)) {
                    cancelTimeout(pendingExit);
                    publishResult(pendingExit, ExitResult.FAILED);
                    return null;
                }
                continue;
            }
            if (PENDING_EXIT.compareAndSet(pendingExit, null)) {
                cancelTimeout(pendingExit);
                return pendingExit;
            }
        }
    }

    private static boolean execute(String action) {
        switch (requireAction(action)) {
            case CLOSE -> {
                OSOperateUtil.closeWindows(JcefContext.getInstance().getFrame_());
                return true;
            }
            case RESTART -> {
                try {
                    Updater.getInstance().restartAppNow();
                    return true;
                } catch (IOException e) {
                    throw new IllegalStateException("Could not restart the application", e);
                }
            }
            case INSTALL_UPDATE -> {
                return Updater.getInstance().triggerInstallationWithAuxiliaryProcessNow();
            }
        }
        throw new IllegalStateException("Unsupported exit action: " + action);
    }

    private static void publishResult(PendingExit pendingExit, ExitResult exitResult) {
        CefBrowser browser = JcefContext.getInstance().getBrowser_();
        if (browser == null) {
            return;
        }
        ConsoleResult result = ConsoleResult.builder()
                .actionType(ActionTypeEnum.APP_EXIT_RESULT.getName())
                .message(Map.of(
                        "reason", pendingExit.action().name(),
                        "operationId", pendingExit.operationId(),
                        "result", exitResult.name()
                ))
                .build();
        try {
            CallJsFunctionUtil.callHandleJavaMessage(browser, JSON.toJSONString(result));
        } catch (RuntimeException ignored) {
            // The request result remains available through the command response.
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

    static void setNanoClockForTests(LongSupplier clock) {
        nanoClock = Objects.requireNonNull(clock, "Clock is required");
    }

    static void setTimeoutSchedulerForTests(TimeoutScheduler scheduler) {
        timeoutScheduler = Objects.requireNonNull(scheduler, "Timeout scheduler is required");
    }

    static void resetForTests() {
        PendingExit pendingExit = PENDING_EXIT.getAndSet(null);
        cancelTimeout(pendingExit);
        nanoClock = System::nanoTime;
        timeoutScheduler = (task, delay, unit) -> {
            var future = TIMEOUT_EXECUTOR.schedule(task, delay, unit);
            return () -> future.cancel(false);
        };
    }

    private static boolean isExpired(PendingExit pendingExit, long nowNanos) {
        return nowNanos - pendingExit.createdAtNanos() >= PENDING_EXIT_TIMEOUT_NANOS;
    }

    private static void expirePending(PendingExit pendingExit) {
        if (PENDING_EXIT.compareAndSet(pendingExit, null)) {
            cancelTimeout(pendingExit);
            publishResult(pendingExit, ExitResult.FAILED);
        }
    }

    private static void cancelTimeout(PendingExit pendingExit) {
        if (pendingExit == null) {
            return;
        }
        TimeoutHandle timeoutHandle = pendingExit.timeoutHandle().getAndSet(null);
        if (timeoutHandle != null) {
            timeoutHandle.cancel();
        }
    }

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "chat2db-exit-timeout");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @FunctionalInterface
    interface TimeoutScheduler {
        TimeoutHandle schedule(Runnable task, long delay, TimeUnit unit);
    }

    @FunctionalInterface
    interface TimeoutHandle {
        void cancel();
    }

    public record Confirmation(boolean accepted, ExitAction action) {
        private static Confirmation rejected() {
            return new Confirmation(false, null);
        }
    }

    private record PendingExit(ExitAction action, String operationId, BooleanSupplier confirmedAction,
                               long createdAtNanos, AtomicReference<TimeoutHandle> timeoutHandle) {
        private PendingExit(ExitAction action, String operationId, BooleanSupplier confirmedAction,
                            long createdAtNanos) {
            this(action, operationId, confirmedAction, createdAtNanos, new AtomicReference<>());
        }
    }
}
