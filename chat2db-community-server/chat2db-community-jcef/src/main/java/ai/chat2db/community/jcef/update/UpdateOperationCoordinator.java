package ai.chat2db.community.jcef.update;

import ai.chat2db.community.tools.exception.BusinessException;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/**
 * Owns the mutable lifecycle of one desktop update attempt.
 *
 * <p>The updater used to represent this lifecycle with independent flags. Keeping the result,
 * downloaded payloads and transition guard together makes illegal combinations (for example,
 * downloading while installation is active) impossible to represent.</p>
 */
final class UpdateOperationCoordinator {

    enum State {
        IDLE,
        CHECKING,
        AVAILABLE,
        DOWNLOADING,
        READY_TO_INSTALL,
        INSTALLING
    }

    record CheckOperation(CompletableFuture<Updater.CheckResult> future, boolean started) {
    }

    private final Object lock = new Object();
    private final Map<String, Path> downloadedFiles = new HashMap<>();

    private Updater.CheckResult checkResult = new Updater.CheckResult();
    private CompletableFuture<Updater.CheckResult> activeCheck;
    private State state = State.IDLE;

    CheckOperation beginCheck() {
        synchronized (lock) {
            if (state == State.DOWNLOADING || state == State.READY_TO_INSTALL || state == State.INSTALLING) {
                return new CheckOperation(CompletableFuture.completedFuture(checkResult), false);
            }
            if (activeCheck == null) {
                activeCheck = new CompletableFuture<>();
                state = State.CHECKING;
                return new CheckOperation(activeCheck, true);
            }
            return new CheckOperation(activeCheck, false);
        }
    }

    void completeCheck(Updater.CheckResult result) {
        synchronized (lock) {
            checkResult = result;
            state = result.isNeedsUpdate() && !result.isCheckFailed() ? State.AVAILABLE : State.IDLE;
            activeCheck.complete(result);
            activeCheck = null;
        }
    }

    Updater.CheckResult beginDownload() {
        synchronized (lock) {
            if (activeCheck != null || state == State.DOWNLOADING || state == State.INSTALLING) {
                throw new BusinessException("Another update operation is already in progress.");
            }
            if (state == State.READY_TO_INSTALL) {
                throw new BusinessException("An update has already been downloaded and is ready to install.");
            }
            if (state != State.AVAILABLE || !checkResult.isNeedsUpdate()
                    || checkResult.getAvailableSnapshot() == null) {
                throw new BusinessException("Check for an available update before downloading it.");
            }
            state = State.DOWNLOADING;
            return checkResult;
        }
    }

    void replaceCheckResult(UnaryOperator<Updater.CheckResult> updater) {
        synchronized (lock) {
            checkResult = updater.apply(checkResult);
        }
    }

    void markDownloadReady() {
        synchronized (lock) {
            requireState(State.DOWNLOADING);
            state = State.READY_TO_INSTALL;
        }
    }

    void finishDownloadAfterFailure() {
        synchronized (lock) {
            if (state == State.DOWNLOADING) {
                state = State.AVAILABLE;
            }
        }
    }

    void beginInstallation() {
        synchronized (lock) {
            if (state == State.DOWNLOADING || state == State.INSTALLING) {
                throw new BusinessException("Update installation is already in progress.");
            }
            if (state != State.READY_TO_INSTALL) {
                throw new BusinessException("No downloaded update is ready to install.");
            }
            state = State.INSTALLING;
        }
    }

    void completeInstallation() {
        synchronized (lock) {
            requireState(State.INSTALLING);
            downloadedFiles.clear();
            checkResult = new Updater.CheckResult();
            state = State.IDLE;
        }
    }

    void finishInstallationAfterFailure() {
        synchronized (lock) {
            if (state == State.INSTALLING) {
                state = State.AVAILABLE;
            }
        }
    }

    /**
     * A Windows helper launch failure occurs before the helper can modify application files, so
     * callers may retry installation with the already verified payloads.
     */
    void restoreReadyToInstallAfterLaunchFailure() {
        synchronized (lock) {
            if (state == State.INSTALLING) {
                state = State.READY_TO_INSTALL;
            }
        }
    }

    Updater.CheckResult currentCheckResult() {
        synchronized (lock) {
            return checkResult;
        }
    }

    State currentState() {
        synchronized (lock) {
            return state;
        }
    }

    void rememberDownloadedFile(String id, Path path) {
        synchronized (lock) {
            downloadedFiles.put(id, path);
        }
    }

    Path downloadedFile(String id) {
        synchronized (lock) {
            return downloadedFiles.get(id);
        }
    }

    Map<String, Path> downloadedFilesSnapshot() {
        synchronized (lock) {
            return Map.copyOf(downloadedFiles);
        }
    }

    Map<String, Path> downloadedFilesForCurrentOperation() {
        synchronized (lock) {
            return new HashMap<>(downloadedFiles);
        }
    }

    void clearDownloadedFiles() {
        synchronized (lock) {
            downloadedFiles.clear();
        }
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected update state " + expected + " but was " + state);
        }
    }
}
