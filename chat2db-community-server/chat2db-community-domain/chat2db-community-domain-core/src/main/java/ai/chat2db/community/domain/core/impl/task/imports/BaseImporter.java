package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.community.domain.api.model.task.ImportAsyncContext;
import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;


@Slf4j
public abstract class BaseImporter implements IImportStrategy {

    public static final int BATCH_SIZE = 100;

    @Override
    public void run(ImportAsyncContext context) {
        context.checkCancelled();
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        IDbMetaData metadata = Chat2DBContext.getDbMetaData();
        List<TableColumn> tableColumns = metadata.columns(Chat2DBContext.getConnection(),
                new TableMetadataRequest(connectInfo.getDatabaseName(), connectInfo.getSchemaName(), context.getTableName()));
        context.checkCancelled();
        context.setProgress(20);
        context.info("Get table columns success.");
        doImportData(context, tableColumns);
    }


    protected abstract void doImportData(ImportAsyncContext asyncContext, List<TableColumn> tableColumns);


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
