package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.task.ImportFailurePolicy;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;


@Slf4j
public abstract class BaseImporter implements IImportStrategy {

    /**
     * Rows buffered before one JDBC batch is executed. Aligned with the spreadsheet importer so
     * every non-parallel import path flushes at the same cadence.
     */
    public static final int BATCH_SIZE = 1000;

    @Override
    public void run(ImportTaskSpec spec, TaskExecutionContext context) {
        try {
            context.checkCancelled();
            validatePolicies(spec);
            logResume(spec, context);
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            IDbMetaData metadata = Chat2DBContext.getDbMetaData();
            List<TableColumn> tableColumns = metadata.columns(Chat2DBContext.getConnection(),
                    new TableMetadataRequest(connectInfo.getDatabaseName(), connectInfo.getSchemaName(),
                            spec.getTarget().getTableName()));
            context.checkCancelled();
            context.reportProgress(20, TaskStage.READING.name(), "Target table metadata loaded");
            context.logInfo(TaskEventCode.TARGET_METADATA_LOADED.name(), "Target table metadata loaded");
            context.logInfo(TaskEventCode.FILE_READ_STARTED.name(), "Reading import file");
            doImportData(spec, context, tableColumns);
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not import data file", e);
            throw ImportTaskErrors.from(e, "import.file.failed");
        }
    }


    /**
     * Reads the source and finishes its writes. Implementations report FILE_READ_COMPLETED at the
     * reader boundary, before flushing any tail batch or waiting for outstanding parallel writes.
     * Full batches may already have been written while the reader was producing rows.
     */
    protected abstract void doImportData(ImportTaskSpec spec, TaskExecutionContext context,
            List<TableColumn> tableColumns);

    /**
     * Rejects an unusable policy before any row is read. A bad value must not surface as a batch
     * failure halfway through the file, and it must never fall back to the default silently.
     */
    private static void validatePolicies(ImportTaskSpec spec) {
        try {
            ImportFailurePolicy.from(spec.getFailurePolicy());
            ImportResumability.duplicatePolicy(spec);
        } catch (IllegalArgumentException e) {
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(), e.getMessage(), e);
        }
    }

    /**
     * Tells the log that a resumed task continues after an already stored prefix. Skipping is
     * positional, so the events state the precondition that makes the row numbers meaningful.
     */
    private static void logResume(ImportTaskSpec spec, TaskExecutionContext context) {
        long resumeFromRow = ImportResumability.resumeOffset(spec);
        if (resumeFromRow <= 0L) {
            return;
        }
        context.logInfo(TaskEventCode.IMPORT_RESUME_STARTED.name(),
                "Continuing import after row " + resumeFromRow
                        + "; rows are skipped by position, so the source file must be unchanged",
                Map.of("resumeFromRow", resumeFromRow,
                        "duplicatePolicy", ImportResumability.duplicatePolicy(spec).name()));
    }


    protected SQLDataValue getSQLDataValue(String value, TableColumn column) {
        DataType dataType = new DataType();
        dataType.setDataTypeName(column.getColumnType());
        dataType.setScale(column.getDecimalDigits());
        dataType.setPrecision(column.getColumnSize());
        SQLDataValue sqlDataValue = new SQLDataValue();
        sqlDataValue.setDataType(dataType);
        sqlDataValue.setValue(value);
        return sqlDataValue;
    }
}
