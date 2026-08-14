package ai.chat2db.community.jcef.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Defines the all-or-nothing boundary for an in-process update installation.
 * File actions register compensating operations before making each mutation; metadata is written
 * by the work only after all file actions have completed.
 */
final class UpdateTransaction {

    @FunctionalInterface
    interface Work {
        void apply(RollbackRegistry rollbackRegistry) throws Exception;
    }

    @FunctionalInterface
    interface RollbackOperation {
        void rollback() throws Exception;
    }

    static final class RollbackRegistry {
        private final List<RollbackOperation> operations = new ArrayList<>();

        void add(RollbackOperation operation) {
            operations.add(Objects.requireNonNull(operation, "rollback operation is required"));
        }

        private boolean rollback(Consumer<String> progressLog) {
            boolean restored = true;
            for (int index = operations.size() - 1; index >= 0; index--) {
                try {
                    operations.get(index).rollback();
                } catch (Exception exception) {
                    progressLog.accept("ERROR during rollback operation: " + exception.getMessage());
                    restored = false;
                }
            }
            return restored;
        }
    }

    private final Consumer<String> progressLog;
    private final Consumer<Exception> errorLog;

    UpdateTransaction(Consumer<String> progressLog, Consumer<Exception> errorLog) {
        this.progressLog = Objects.requireNonNull(progressLog, "progressLog is required");
        this.errorLog = Objects.requireNonNull(errorLog, "errorLog is required");
    }

    boolean execute(Work work) {
        RollbackRegistry rollbackRegistry = begin();
        try {
            work.apply(rollbackRegistry);
            return true;
        } catch (Exception exception) {
            fail(rollbackRegistry, exception);
            return false;
        }
    }

    RollbackRegistry begin() {
        return new RollbackRegistry();
    }

    boolean fail(RollbackRegistry rollbackRegistry, Exception exception) {
        errorLog.accept(exception);
        progressLog.accept("ERROR during update execution: " + exception.getMessage());
        return rollbackRegistry.rollback(progressLog);
    }
}
