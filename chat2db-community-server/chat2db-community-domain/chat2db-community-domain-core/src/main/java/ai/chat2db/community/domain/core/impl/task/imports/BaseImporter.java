package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.community.domain.api.model.metadata.DataType;
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


@Slf4j
public abstract class BaseImporter implements IImportStrategy {

    public static final int BATCH_SIZE = 100;

    @Override
    public void run(ImportTaskSpec spec, TaskExecutionContext context) {
        try {
            context.checkCancelled();
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            IDbMetaData metadata = Chat2DBContext.getDbMetaData();
            List<TableColumn> tableColumns = metadata.columns(Chat2DBContext.getConnection(),
                    new TableMetadataRequest(connectInfo.getDatabaseName(), connectInfo.getSchemaName(),
                            spec.getTarget().getTableName()));
            context.checkCancelled();
            context.reportProgress(20, TaskStage.READING.name(), "Target table metadata loaded");
            context.logInfo(TaskEventCode.FILE_READ_STARTED.name(), "Reading import file");
            doImportData(spec, context, tableColumns);
            context.logInfo(TaskEventCode.FILE_READ_COMPLETED.name(), "Import file read completed");
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not import data file", e);
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import data file", e);
        }
    }


    protected abstract void doImportData(ImportTaskSpec spec, TaskExecutionContext context,
            List<TableColumn> tableColumns);


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
