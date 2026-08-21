package ai.chat2db.plugin.mariadb;

import java.sql.Connection;

import ai.chat2db.plugin.mysql.MysqlDBManager;
import ai.chat2db.spi.IDbManager;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;

import static ai.chat2db.plugin.mariadb.constant.MariaDBManagerConstants.*;
public class MariaDBManager extends MysqlDBManager implements IDbManager {


    @Override
    public void exportTableData(Connection connection, String databaseName, String schemaName, String tableName,
                                TaskExecutionContext context) {
        exportTableData(connection, databaseName, schemaName, tableName, context, EXPORT_FETCH_SIZE);
    }
}
