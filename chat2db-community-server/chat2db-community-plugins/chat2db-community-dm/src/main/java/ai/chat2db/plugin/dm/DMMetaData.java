package ai.chat2db.plugin.dm;

import ai.chat2db.plugin.dm.builder.DMSqlBuilder;
import ai.chat2db.plugin.dm.identifier.DMIdentifierProcessor;
import ai.chat2db.plugin.dm.enums.type.DMColumnTypeEnum;
import ai.chat2db.plugin.dm.enums.type.DMDefaultValueEnum;
import ai.chat2db.plugin.dm.enums.type.DMIndexTypeEnum;
import ai.chat2db.plugin.dm.value.DMValueProcessor;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.community.domain.api.model.account.*;
import ai.chat2db.community.domain.api.model.async.*;
import ai.chat2db.community.domain.api.config.*;
import ai.chat2db.spi.model.datasource.*;
import ai.chat2db.community.domain.api.model.form.*;
import ai.chat2db.community.domain.api.model.metadata.*;
import ai.chat2db.community.domain.api.model.result.*;
import ai.chat2db.community.domain.api.model.sql.*;
import ai.chat2db.spi.model.value.*;
import ai.chat2db.community.domain.api.model.view.*;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.SortUtils;
import com.google.common.collect.Lists;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.dm.constant.DMMetaDataConstants.*;
@Slf4j
public class DMMetaData extends DefaultMetaService implements IDbMetaData {

    @Override
    public List<Schema> schemas(Connection connection, String databaseName) {
        List<Schema> schemas = DefaultSQLExecutor.getInstance().schemas(connection, databaseName, null);
        return SortUtils.sortSchema(schemas, SYSTEM_SCHEMAS);
    }

    private String format(String tableName) {
        return DMIdentifierProcessor.INSTANCE.quoteIdentifierAlways(tableName);
    }

    protected static String tableDDL = "SELECT dbms_metadata.get_ddl('TABLE', '%s','%s') as ddl FROM dual ;";

    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        String tableDDLSql = String.format(tableDDL, getSQLIdentifierProcessor().escapeString(tableName), getSQLIdentifierProcessor().escapeString(schemaName));
        StringBuilder ddlBuilder = new StringBuilder();
        DefaultSQLExecutor.getInstance().execute(connection, tableDDLSql, resultSet -> {
            if (resultSet.next()) {
                String ddl = resultSet.getString("ddl");
                ddlBuilder.append(ddl).append("\n");
            }
        });
        List<Table> tables = this.tables(connection, databaseName, schemaName, tableName);
        if (CollectionUtils.isNotEmpty(tables)) {
            String tableComment = tables.get(0).getComment();
            if (StringUtils.isNotBlank(tableComment)) {
                ddlBuilder.append(SQL_COMMENT_TABLE).append(format(schemaName)).append(".").append(format(tableName))
                        .append(" IS '").append(getSQLIdentifierProcessor().escapeString(tableComment)).append("'").append(";").append("\n");
            }
        }
        List<TableColumn> columns = this.columns(connection, databaseName, schemaName, tableName);
        if (CollectionUtils.isNotEmpty(columns)) {
            for (TableColumn column : columns) {
                String columnName = column.getName();
                String comment = column.getComment();
                if (StringUtils.isNotBlank(comment)) {
                    ddlBuilder.append(SQL_COMMENT_COLUMN).append(format(schemaName)).append(".").append(format(tableName))
                            .append(".").append(format(columnName)).append(" IS ")
                            .append("'").append(getSQLIdentifierProcessor().escapeString(comment))
                            .append("';").append("\n");
                }
            }
        }
        if (tableName.startsWith("V$")) {
            return ddlBuilder.toString();
        }
        List<TableIndex> indexes = this.indexes(connection, databaseName, schemaName, tableName);
        List<String> uniqueConstraintIndexName = null;
        if (CollectionUtils.isNotEmpty(indexes)) {
            try {
                String sql = "select INDEX_NAME from  sys.all_constraints where OWNER=? and TABLE_NAME=? and CONSTRAINT_TYPE = 'U' ;";
                uniqueConstraintIndexName = DefaultSQLExecutor.getInstance().preExecute(connection, sql, new String[]{schemaName, tableName}, resultSet -> {
                    List<String> indexNames = new ArrayList<>(5);
                    while (resultSet.next()) {
                        String indexName = resultSet.getString("INDEX_NAME");
                        if (StringUtils.isNotBlank(indexName)) {
                            indexNames.add(indexName);
                        }
                    }
                    return indexNames;
                });
            } catch (Exception e) {
                log.error(" get unique constraint index name error", e);
            }
        }
        for (TableIndex index : indexes) {
            String indexName = index.getName();
            boolean isPrimaryKey = "primary".equalsIgnoreCase(index.getType());
            boolean isUniqueConstraint = uniqueConstraintIndexName != null && uniqueConstraintIndexName.contains(indexName);

            if (StringUtils.isNotBlank(indexName) && !isPrimaryKey && !isUniqueConstraint) {
                String sql = "select DBMS_METADATA.GET_DDL('INDEX','%s') as INDEX_DDL";
                try {
                    DefaultSQLExecutor.getInstance().execute(connection, String.format(sql, getSQLIdentifierProcessor().escapeString(indexName)), resultSet -> {
                        if (resultSet.next()) {
                            ddlBuilder.append(resultSet.getString("INDEX_DDL")).append("\n");
                        }
                    });
                } catch (Exception e) {
                    log.warn("Failed to get the DDL of the index: {}", indexName, e);
                    DMIndexTypeEnum indexTypeEnum = DMIndexTypeEnum.getByType(index.getType());
                    if (Objects.nonNull(indexTypeEnum)) {
                        ddlBuilder.append("\n").append(indexTypeEnum.buildIndexScript(index)).append(";");
                    }
                }
            }
        }
        return ddlBuilder.toString();
    }


    @Override
    public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
        List<TableColumn> columns = super.columns(connection, databaseName, schemaName, tableName);
        for (TableColumn column : columns) {
            String columnType = column.getColumnType();
            if (columnType != null
                    && StringUtils.equals(columnType.toUpperCase(Locale.ROOT), DMColumnTypeEnum.TIMESTAMP.name())) {
                column.setColumnSize(column.getDecimalDigits());
            }
        }
        return columns;
    }



    @Override
    public Function function(Connection connection, @NotEmpty String databaseName, String schemaName,
                             String functionName) {

        String sql = String.format(ROUTINES_SQL, "PROC", getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(functionName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            StringBuilder sb = new StringBuilder();
            while (resultSet.next()) {
                sb.append(resultSet.getString("TEXT") + "\n");
            }
            Function function = new Function();
            function.setDatabaseName(databaseName);
            function.setSchemaName(schemaName);
            function.setFunctionName(functionName);
            function.setFunctionBody(sb.toString());
            return function;

        });

    }

    @Override
    public Procedure procedure(Connection connection, @NotEmpty String databaseName, String schemaName,
                               String procedureName) {
        String sql = String.format(ROUTINES_SQL, "PROC", getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(procedureName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            StringBuilder sb = new StringBuilder();
            while (resultSet.next()) {
                sb.append(resultSet.getString("TEXT") + "\n");
            }
            Procedure procedure = new Procedure();
            procedure.setDatabaseName(databaseName);
            procedure.setSchemaName(schemaName);
            procedure.setProcedureName(procedureName);
            procedure.setProcedureBody(sb.toString());
            return procedure;
        });
    }





    @Override
    public List<Trigger> triggers(Connection connection, String databaseName, String schemaName) {
        List<Trigger> triggers = new ArrayList<>();
        String sql = String.format(TRIGGER_SQL_LIST, getSQLIdentifierProcessor().escapeString(schemaName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            while (resultSet.next()) {
                Trigger trigger = new Trigger();
                trigger.setTriggerName(resultSet.getString("TRIGGER_NAME"));
                trigger.setSchemaName(schemaName);
                trigger.setDatabaseName(databaseName);
                triggers.add(trigger);
            }
            return triggers;
        });
    }

    @Override
    public Trigger trigger(Connection connection, @NotEmpty String databaseName, String schemaName,
                           String triggerName) {

        String sql = String.format(TRIGGER_SQL, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(triggerName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Trigger trigger = new Trigger();
            trigger.setDatabaseName(databaseName);
            trigger.setSchemaName(schemaName);
            trigger.setTriggerName(triggerName);
            if (resultSet.next()) {
                trigger.setTriggerBody(resultSet.getString("TRIGGER_BODY"));
            }
            return trigger;
        });
    }



    @Override
    public Table view(Connection connection, String databaseName, String schemaName, String viewName) {
        String sql = String.format(VIEW_SQL, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(viewName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            Table table = new Table();
            table.setDatabaseName(databaseName);
            table.setSchemaName(schemaName);
            table.setName(viewName);
            if (resultSet.next()) {
                table.setDdl(resultSet.getString("TEXT"));
            }
            return table;
        });
    }



    @Override
    public List<TableIndex> indexes(Connection connection, String databaseName, String schemaName, String tableName) {
        String sql = String.format(INDEX_SQL, getSQLIdentifierProcessor().escapeString(schemaName), getSQLIdentifierProcessor().escapeString(tableName));
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            LinkedHashMap<String, TableIndex> map = new LinkedHashMap();
            while (resultSet.next()) {
                String keyName = resultSet.getString("INDEX_NAME");
                TableIndex tableIndex = map.get(keyName);
                if (tableIndex != null) {
                    List<TableIndexColumn> columnList = tableIndex.getColumnList();
                    columnList.add(getTableIndexColumn(resultSet));
                    columnList = columnList.stream().sorted(Comparator.comparing(TableIndexColumn::getOrdinalPosition))
                            .collect(Collectors.toList());
                    tableIndex.setColumnList(columnList);
                } else {
                    TableIndex index = new TableIndex();
                    index.setDatabaseName(databaseName);
                    index.setSchemaName(schemaName);
                    index.setTableName(tableName);
                    index.setName(keyName);
                    index.setUnique("UNIQUE".equalsIgnoreCase(resultSet.getString("UNIQUENESS")));
                    List<TableIndexColumn> tableIndexColumns = new ArrayList<>();
                    tableIndexColumns.add(getTableIndexColumn(resultSet));
                    index.setColumnList(tableIndexColumns);
                    if ("P".equalsIgnoreCase(resultSet.getString("CONSTRAINT_TYPE"))) {
                        index.setType(DMIndexTypeEnum.PRIMARY_KEY.getName());
                    } else if (index.getUnique()) {
                        index.setType(DMIndexTypeEnum.UNIQUE.getName());
                    } else if ("BITMAP".equalsIgnoreCase(resultSet.getString("INDEX_TYPE"))) {
                        index.setType(DMIndexTypeEnum.BITMAP.getName());
                    } else {
                        index.setType(DMIndexTypeEnum.NORMAL.getName());
                    }
                    map.put(keyName, index);
                }
            }
            return map.values().stream().collect(Collectors.toList());
        });

    }

    private TableIndexColumn getTableIndexColumn(ResultSet resultSet) throws SQLException {
        TableIndexColumn tableIndexColumn = new TableIndexColumn();
        tableIndexColumn.setColumnName(resultSet.getString("COLUMN_NAME"));
        tableIndexColumn.setOrdinalPosition(resultSet.getShort("COLUMN_POSITION"));
        String collation = resultSet.getString("DESCEND");
        if ("ASC".equalsIgnoreCase(collation)) {
            tableIndexColumn.setAscOrDesc("ASC");
        } else if ("DESC".equalsIgnoreCase(collation)) {
            tableIndexColumn.setAscOrDesc("DESC");
        }
        return tableIndexColumn;
    }

    @Override
    public ISqlBuilder getSqlBuilder() {
        return new DMSqlBuilder();
    }

    @Override
    public TableMeta getTableMeta(String databaseName, String schemaName, String tableName) {
        return TableMeta.builder()
                .columnTypes(DMColumnTypeEnum.getTypes())
                .charsets(Lists.newArrayList())
                .collations(Lists.newArrayList())
                .indexTypes(DMIndexTypeEnum.getIndexTypes())
                .defaultValues(DMDefaultValueEnum.getDefaultValues())
                .build();
    }

    @Override
    public IValueProcessor getValueProcessor() {
        return new DMValueProcessor();
    }

    @Override
    public ISQLIdentifierProcessor getSQLIdentifierProcessor() {
        return DMIdentifierProcessor.INSTANCE;
    }

    @Override
    public String getMetaDataName(String... names) {
        if (names.length == 3) {
            String qualifier = StringUtils.isNotBlank(names[1]) ? names[1] : names[0];
            return getMetaDataName(qualifier, names[2]);
        }
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(DMIdentifierProcessor.INSTANCE::quoteIdentifierAlways).collect(Collectors.joining("."));
    }


    @Override
    public List<String> getSystemSchemas() {
        return SYSTEM_SCHEMAS;
    }

    @Override
    public Boolean supportCrossSchema() {
        return Boolean.TRUE;
    }
}
