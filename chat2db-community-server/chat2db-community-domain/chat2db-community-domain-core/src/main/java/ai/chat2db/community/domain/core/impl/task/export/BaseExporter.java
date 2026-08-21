package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskConstants;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;


@Slf4j
public abstract class BaseExporter implements IExportStrategy {

    protected String contentType;

    protected String suffix;
    public static int BATCH_SIZE = 1000;

    @Override
    public void run(ExportTaskSpec spec, TaskExecutionContext context, File outputFile) {
        context.checkCancelled();
        List<String> tableNames = spec.getTableNames();
        if (CollectionUtils.isEmpty(tableNames)) {
            throw new IllegalArgumentException("tableNames should not be null or empty");
        }
        try {
            if (tableNames.size() == 1) {
                context.reportProgress(20, TaskStage.EXPORTING.name(), "Exporting table data");
                single(spec, context, outputFile, 0, 1);
            } else {
                multi(spec, context, outputFile);
            }
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("export data error", e);
            throw new TaskExecutionException(TaskErrorCode.EXPORT_FAILED.name(), "Could not export table data", e);
        }
    }

    private void single(ExportTaskSpec spec, TaskExecutionContext context, File outputFile, int tableIndex,
            int totalTables) throws Exception {
        String tableName = spec.getTableNames().get(0);
        logTableEvent(context, TaskEventCode.TABLE_EXPORT_STARTED.name(),
                tableProgressMessage("Exporting table", tableName, tableIndex, totalTables), tableName,
                tableIndex, totalTables);
        singleExport(spec, context, tableName, outputFile);
        logTableEvent(context, TaskEventCode.TABLE_EXPORT_COMPLETED.name(),
                tableProgressMessage("Table export completed", tableName, tableIndex, totalTables), tableName,
                tableIndex, totalTables);
    }

    private void multi(ExportTaskSpec spec, TaskExecutionContext context, File outputFile) throws Exception {
        File parent = outputFile.getAbsoluteFile().getParentFile();
        FileUtil.mkdir(parent);
        File temporaryDirectory = Files.createTempDirectory(parent.toPath(), ".task-export-").toFile();
        int n = spec.getTableNames().size();
        List<File> intermediateFiles = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                context.checkCancelled();
                String tableName = spec.getTableNames().get(i);
                if (StringUtils.isEmpty(tableName)) {
                    throw new IllegalArgumentException("tableName should not be null or empty");
                }
                String safeTableName = new File(tableName).getName();
                File file = new File(temporaryDirectory, safeTableName + suffix);
                intermediateFiles.add(file);
                logTableEvent(context, TaskEventCode.TABLE_EXPORT_STARTED.name(),
                        tableProgressMessage("Exporting table", tableName, i, n), tableName, i, n);
                singleExport(spec, context, tableName, file);
                logTableEvent(context, TaskEventCode.TABLE_EXPORT_COMPLETED.name(),
                        tableProgressMessage("Table export completed", tableName, i, n), tableName, i, n);
                context.reportProgress(Math.min(90, 10 + ((i + 1) * 80 / n)), TaskStage.EXPORTING.name(),
                        "Exported " + (i + 1) + " of " + n + " tables");
            }
            context.checkCancelled();
            context.reportProgress(92, TaskStage.FINALIZING.name(), "Finalizing ZIP export archive");
            context.logInfo(TaskEventCode.FILE_FINALIZING.name(), "Finalizing ZIP export archive",
                    Map.of(TaskConstants.FILE_FORMAT_DETAIL_KEY, "ZIP",
                            TaskConstants.TOTAL_TABLES_DETAIL_KEY, n));
            zip(context, outputFile, intermediateFiles);
        } finally {
            for (File file : intermediateFiles) {
                FileUtil.del(file);
            }
            FileUtil.del(temporaryDirectory);
        }
    }

    private void zip(TaskExecutionContext context, File outputFile, List<File> files) throws IOException {
        byte[] buffer = new byte[8192];
        try (ZipOutputStream output = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(outputFile.toPath())))) {
            for (File file : files) {
                context.checkCancelled();
                output.putNextEntry(new ZipEntry(file.getName()));
                try (InputStream input = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        context.checkCancelled();
                        output.write(buffer, 0, length);
                    }
                } finally {
                    output.closeEntry();
                }
            }
        }
    }

    protected String getQuerySql(ExportTaskSpec spec, String tableName) {
        String databaseName = spec.getTarget().getDatabaseName();
        String schemaName = spec.getTarget().getSchemaName();
        return Chat2DBContext.getSqlBuilder().dql().buildSelectTable(databaseName, schemaName, tableName);
    }

    private String tableProgressMessage(String prefix, String tableName, int tableIndex, int totalTables) {
        return prefix + " " + (tableIndex + 1) + "/" + totalTables + ": " + tableName;
    }

    private void logTableEvent(TaskExecutionContext context, String code, String message, String tableName,
            int tableIndex, int totalTables) {
        context.logInfo(code, message,
                Map.of(TaskConstants.TABLE_NAME_DETAIL_KEY, tableName,
                        TaskConstants.EXPORTED_TABLES_DETAIL_KEY, tableIndex + 1,
                        TaskConstants.TOTAL_TABLES_DETAIL_KEY, totalTables));
    }

    protected abstract void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName,
            File file) throws Exception;

}
