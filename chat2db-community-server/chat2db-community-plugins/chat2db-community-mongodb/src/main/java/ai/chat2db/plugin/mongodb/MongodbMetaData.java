package ai.chat2db.plugin.mongodb;

import ai.chat2db.community.tools.wrapper.result.PageResult;
import ai.chat2db.spi.ICommandExecutor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.converter.DocumentConverter;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.SortUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

import static ai.chat2db.plugin.mongodb.constant.MongodbMetaDataConstants.*;
@Slf4j
public class MongodbMetaData extends DefaultMetaService implements IDbMetaData {



    public List<Database> databases(Connection connection) {
        List schemas = (List) DefaultSQLExecutor.getInstance().execute(connection, SQL_SHOW_DBS, (resultSet) -> {
            List<Database> databases = new ArrayList();
            while (resultSet.next()) {
                Database database = new Database();
                Object o = resultSet.getObject(1);
                String name = String.valueOf(DocumentConverter.object2map(o).get("name"));
                database.setName(name);
                databases.add(database);
            }

            return databases;
        });
        return SortUtils.sortSchema(schemas, SYSTEM_SCHEMAS);
    }


    @Override
    public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
        executeUse(schemaName);
        return DefaultSQLExecutor.getInstance().execute(connection, SQL_SHOW_TABLES, resultSet -> {
            List<Table> tables = new ArrayList<>();
            while (resultSet.next()) {
                Object o = resultSet.getObject(1);
                String name = String.valueOf(DocumentConverter.object2map(o).get("name"));
                Table table = new Table();
                table.setName(name);
                tables.add(table);
            }
            return tables;
        });
    }

    @Override
    public PageResult<Table> tables(Connection connection, String databaseName, String schemaName,
        String tableNamePattern, int pageNo, int pageSize) {
        executeUse(schemaName);
        List<Table> tableList = DefaultSQLExecutor.getInstance().execute(connection, SQL_SHOW_TABLES, resultSet -> {
            List<Table> tables = new ArrayList<>();
            while (resultSet.next()) {
                Object o = resultSet.getObject(1);
                String name = String.valueOf(DocumentConverter.object2map(o).get("name"));
                Table table = new Table();
                table.setName(name);
                tables.add(table);
            }
            return tables;
        });
        tableList.sort(Comparator.comparing(Table::getName, String.CASE_INSENSITIVE_ORDER));
        return PageResult.of(tableList, (long) tableList.size(), pageNo, pageSize);
    }

    @Override
    public List columns(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(SELECT_TABLE_COLUMNS, MongodbSqlGuards.collectionAccessor(tableName));
        List<TableColumn> tableColumns = new ArrayList();
        return (List) DefaultSQLExecutor.getInstance().execute(connection, sql, (resultSet) -> {
            while (resultSet.next()) {
                Object o = resultSet.getObject(1);
                Map<String, Object> objectMap = copyDocumentMap(DocumentConverter.object2map(o));
                TableColumn tableColumn = new TableColumn();
                Object columName = objectMap.get("key");
                if (Objects.nonNull(columName)) {
                    tableColumn.setName(columName.toString());
                }
                Object columType = objectMap.get("types");
                if (Objects.nonNull(columType)) {
                    tableColumn.setColumnType(columType.toString());
                }
                tableColumns.add(tableColumn);
            }
            return tableColumns;
        });
    }

    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(SELECT_TABLE_INDEX, MongodbSqlGuards.collectionAccessor(tableName));
        List<TableIndex> tableIndexes = new ArrayList<>();
        DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Object o = resultSet.getObject(1);
                Map<String, Object> objectMap = copyDocumentMap(DocumentConverter.object2map(o));
                Object indexName = objectMap.get("name");
                TableIndex tableIndex = new TableIndex();
                if (Objects.nonNull(indexName)) {
                    tableIndex.setName(indexName.toString());
                }
                tableIndexes.add(tableIndex);
            }
            return tableIndexes;
        });
        return tableIndexes;
    }

    @Override
    public ICommandExecutor getCommandExecutor() {
        return MongodbScriptExecutor.getInstance();
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return MongodbSqlBuilder.getInstance();
    }

    static Map<String, Object> copyDocumentMap(Map<String, Object> map) {
        return new HashMap<>(map);
    }

    private void executeUse(String schemaName) {
        if (StringUtils.isEmpty(schemaName)) {
            return;
        }
        Connection connection = Chat2DBContext.getConnection();
        String sql = String.format(SCRIPT_USE_SCHEMA, MongodbSqlGuards.requireDatabaseName(schemaName));
        try {
            DefaultSQLExecutor.getInstance().execute(connection, sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
