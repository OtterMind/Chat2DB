package ai.chat2db.spi.lineage;

import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.spi.util.SqlStringUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Database;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class JSqlParserLineageFinder {


    public static SimpleSqlStatement findLineage(String sql, DatabaseConfig databaseConfig) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            SimpleSqlStatement simpleSqlStatement = new SimpleSqlStatement(sql);
            if (statement instanceof Select) {
                TableColumnLineage lineage = new TableColumnLineage(databaseConfig);
                Select selectBody = ((Select) statement).getSelectBody();
                if (selectBody instanceof PlainSelect plainSelect) {
                    lineage.visit(plainSelect);
                }
                List<SimpleSqlStatement.SimpleTable> tables = lineage.getLineage();
                simpleSqlStatement.setSqlType(SqlTypeEnum.SELECT.name());
                simpleSqlStatement.setTables(tables);
                return simpleSqlStatement;
            } else if (statement instanceof Insert insert) {
                List<SimpleSqlStatement.SimpleTable> tables = new ArrayList<>();
                Table targetTable = insert.getTable();
                tables.add(processTable(targetTable, databaseConfig));


                simpleSqlStatement.setSqlType(SqlTypeEnum.INSERT.name());
                simpleSqlStatement.setTables(tables);
                return simpleSqlStatement;
            } else if (statement instanceof Update update) {
                List<SimpleSqlStatement.SimpleTable> tables = new ArrayList<>();
                tables.add(processTable(update.getTable(), databaseConfig));

                simpleSqlStatement.setSqlType(SqlTypeEnum.UPDATE.name());
                simpleSqlStatement.setTables(tables);
                return simpleSqlStatement;

            } else if (statement instanceof Delete delete) {
                List<SimpleSqlStatement.SimpleTable> tables = new ArrayList<>();
                Table targetTable = delete.getTable();
                tables.add(processTable(targetTable, databaseConfig));

                simpleSqlStatement.setSqlType(SqlTypeEnum.DELETE.name());
                simpleSqlStatement.setTables(tables);
                return simpleSqlStatement;

            }
        } catch (JSQLParserException e) {
            log.error("parser or find Lineage failed", e);
            throw new RuntimeException("parser or find Lineage failed", e);
        }
        return new SimpleSqlStatement(sql);
    }
    private static SimpleSqlStatement.SimpleTable processTable(Table table, DatabaseConfig databaseConfig) {
        SimpleSqlStatement.SimpleTable simpleTable = new SimpleSqlStatement.SimpleTable();
        simpleTable.setTableName(table.getName());

        String schemaName = table.getSchemaName();
        Database database = table.getDatabase();
        String databaseName = database != null ? database.getDatabaseName() : null;
        if (databaseConfig.isSupportDatabase() && !databaseConfig.isSupportSchema()) {
            databaseName = schemaName;
            schemaName = null;
        }

        simpleTable.setDatabaseName(SqlStringUtil.removeQuote(databaseName, databaseConfig.getDatabaseType()));
        simpleTable.setSchemaName(SqlStringUtil.removeQuote(schemaName, databaseConfig.getDatabaseType()));
        simpleTable.setAlias(table.getAlias() != null ? table.getAlias().getName() : null);
        simpleTable.setColumns(new ArrayList<>());

        return simpleTable;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class DatabaseConfig {
        private String databaseType;
        private boolean supportDatabase;
        private boolean supportSchema;
    }

    public static class TableColumnLineage extends SelectVisitorAdapter {
        private final DatabaseConfig databaseConfig;
        private List<SimpleSqlStatement.SimpleTable> tables = new ArrayList<>();
        private final Map<String, String> tableAliases = new HashMap<>();
        private final Map<String, List<SimpleSqlStatement.Column>> columnMap = new HashMap<>();

        public TableColumnLineage(DatabaseConfig databaseConfig) {
            this.databaseConfig = databaseConfig;
        }


        @Override
        public void visit(PlainSelect plainSelect) {
            processTable(plainSelect.getFromItem());
            List<Join> joins = plainSelect.getJoins();
            if (joins != null) {
                for (Join join : joins) {
                    processTable(join.getRightItem());
                }
            }
            for (SelectItem item : plainSelect.getSelectItems()) {
                if (item.getExpression() instanceof Column column) {
                    String tableAlias = column.getTable() != null ? column.getTable().getName() : null;
                    String columnName = column.getColumnName();
                    String alias = item.getAlias() != null ? item.getAlias().getName() : null;
                    String tableName = tableAlias != null && tableAliases.containsValue(tableAlias)
                            ? tableAliases.entrySet().stream()
                            .filter(e -> e.getValue().equals(tableAlias))
                            .findFirst()
                            .map(Map.Entry::getKey)
                            .orElse(null)
                            : tableAlias;

                    if (tableName != null) {
                        columnMap.computeIfAbsent(tableName, k -> new ArrayList<>())
                                .add(new SimpleSqlStatement.Column(tableName, columnName, alias));
                    }
                }
            }
            buildTables();
        }

        private void processTable(FromItem fromItem) {
            if (fromItem instanceof Table) {
                Table table = (Table) fromItem;
                String tableName = table.getName();
                String schemaName = table.getSchemaName();
                Database database = table.getDatabase();
                String databaseName = database != null ? database.getDatabaseName() : null;
                if (databaseConfig.supportDatabase && !databaseConfig.supportSchema) {
                    databaseName = schemaName;
                    schemaName = null;
                }
                String alias = table.getAlias() != null ? table.getAlias().getName() : null;
                SimpleSqlStatement.SimpleTable simpleTable = new SimpleSqlStatement.SimpleTable();
                simpleTable.setTableName(tableName);
                simpleTable.setDatabaseName(SqlStringUtil.removeQuote(databaseName, databaseConfig.databaseType));
                simpleTable.setSchemaName(SqlStringUtil.removeQuote(schemaName, databaseConfig.databaseType));
                simpleTable.setAlias(alias);
                simpleTable.setColumns(new ArrayList<>());
                tables.add(simpleTable);

                tableAliases.put(tableName, alias);
            }
        }

        private void buildTables() {
            for (SimpleSqlStatement.SimpleTable table : tables) {
                List<SimpleSqlStatement.Column> columns = columnMap.get(table.getTableName());
                if (columns != null) {
                    table.setColumns(columns);
                }
            }
        }
        public List<SimpleSqlStatement.SimpleTable> getLineage() {
            return tables;
        }
    }

    private static void printTables(List<SimpleSqlStatement.SimpleTable> tables) {
        System.out.println("\n=== SQL血缘分析结果 ===");
        if (tables == null || tables.isEmpty()) {
            System.out.println("没有解析到表信息");
            return;
        }

        for (SimpleSqlStatement.SimpleTable table : tables) {
            System.out.println("\n📊 表信息:");
            System.out.printf("   %-12s: %s%n", "表名", table.getTableName());
            if (table.getSchemaName() != null) {
                System.out.printf("   %-12s: %s%n", "模式名", table.getSchemaName());
            }
            if (table.getDatabaseName() != null) {
                System.out.printf("   %-12s: %s%n", "数据库名", table.getDatabaseName());
            }
            System.out.printf("   %-12s: %s%n", "别名",
                    (table.getAlias() != null ? table.getAlias() : "无"));

            System.out.println("   📑 相关字段:");
            if (table.getColumns() != null && !table.getColumns().isEmpty()) {
                for (SimpleSqlStatement.Column column : table.getColumns()) {
                    System.out.printf("      %-20s %s%n",
                            column.getColumnName(),
                            (column.getAlias() != null ? "→ " + column.getAlias() : ""));
                }
            } else {
                System.out.println("      (无字段信息)");
            }
        }
        System.out.println("\n=== 分析完成 ===");
    }

}
