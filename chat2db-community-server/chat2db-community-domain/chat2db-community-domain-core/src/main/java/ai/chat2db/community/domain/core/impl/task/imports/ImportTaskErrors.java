package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.I18nUtils;

/** Preserve actionable import errors through parser/library exception wrappers. */
public final class ImportTaskErrors {
    private ImportTaskErrors() { }

    public static TaskExecutionException from(Exception error, String fallbackMessageKey) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TaskCancelledException cancelled) throw cancelled;
            if (cause instanceof TaskExecutionException taskError) return taskError;
            if (cause instanceof BusinessException businessError) {
                return new TaskExecutionException(businessError.getCode(),
                        I18nUtils.getMessage(businessError.getCode(), businessError.getArgs()), error);
            }
        }
        return new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                I18nUtils.getMessage(fallbackMessageKey), error);
    }
}
