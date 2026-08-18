package ai.chat2db.community.domain.core.impl.task.executor;

import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskFileFormat;
import ai.chat2db.community.domain.api.model.task.TaskSpec;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.Locale;

final class TaskExecutorSupport {

    private TaskExecutorSupport() {
    }

    static String requireFormat(String format) {
        if (StringUtils.isBlank(format)) {
            throw new TaskExecutionException(TaskErrorCode.TASK_INTERNAL_ERROR.name(),
                    "Task file format is required");
        }
        try {
            return TaskFileFormat.valueOf(format.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new TaskExecutionException(TaskErrorCode.TASK_INTERNAL_ERROR.name(),
                    "Unsupported task file format", e);
        }
    }

    static File requireReadableSource(String sourceFile) {
        if (StringUtils.isBlank(sourceFile)) {
            throw new TaskExecutionException(TaskErrorCode.FILE_NOT_FOUND.name(), "Import file is required");
        }
        File file = new File(sourceFile);
        if (!file.isFile() || !file.canRead()) {
            throw new TaskExecutionException(TaskErrorCode.FILE_NOT_FOUND.name(), "Import file is not readable");
        }
        return file;
    }

    static String artifactFileName(TaskSpec spec, String suggestedFileName, String format) {
        String baseName = StringUtils.firstNonBlank(suggestedFileName, spec.getTaskName(), "chat2db-export");
        baseName = new File(baseName).getName();
        String suffix = suffix(format);
        return baseName.toLowerCase(Locale.ROOT).endsWith(suffix) ? baseName : baseName + suffix;
    }

    static String suffix(String format) {
        return switch (TaskFileFormat.valueOf(format)) {
            case CSV -> ".csv";
            case XLS -> ".xls";
            case XLSX -> ".xlsx";
            case JSON -> ".json";
            case SQL -> ".sql";
            case ZIP -> ".zip";
        };
    }

    static String mediaType(String format) {
        return switch (TaskFileFormat.valueOf(format)) {
            case CSV -> "text/csv";
            case XLS -> "application/vnd.ms-excel";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case JSON -> "application/json";
            case SQL -> "text/sql";
            case ZIP -> "application/zip";
        };
    }
}
