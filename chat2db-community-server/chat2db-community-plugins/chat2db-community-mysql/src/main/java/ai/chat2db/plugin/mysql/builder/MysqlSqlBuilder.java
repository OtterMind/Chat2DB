package ai.chat2db.plugin.mysql.builder;

import ai.chat2db.plugin.mysql.MysqlSqlGuards;
import ai.chat2db.plugin.mysql.identifier.MysqlIdentifierProcessor;
import ai.chat2db.plugin.mysql.enums.MysqlViewAlgorithmOptionEnum;
import ai.chat2db.plugin.mysql.enums.MysqlViewCheckOptionEnum;
import ai.chat2db.plugin.mysql.enums.MysqlViewSqlSecurityOptionEnum;
import ai.chat2db.plugin.mysql.enums.type.MysqlColumnTypeEnum;
import ai.chat2db.plugin.mysql.enums.type.MysqlIndexTypeEnum;
import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.spi.DefaultSqlBuilder;
import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.spi.model.request.PageLimitRequest;
import ai.chat2db.spi.sql.builder.ITablespaceSqlBuilder;
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
import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.CREATE_VIEW_SQL_CAPACITY;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.LIMIT_SQL_EXTRA_CAPACITY;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.PREVIOUS_COLUMN_NOT_FOUND;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_AFTER;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_ALGORITHM;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_AUTO_INCREMENT_ASSIGNMENT;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_COLLATE_ASSIGNMENT;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_COMMENT;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_COMMENT_WITH_SINGLE_QUOTE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DEFAULT_CHARACTER_SET_ASSIGNMENT;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DEFINER;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_DROP_DATABASE_TEMPLATE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_ENGINE_ASSIGNMENT;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_FIRST_TERMINATOR;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_LIMIT_ONE_SUFFIX;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_MODIFY_COLUMN;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_PARTITION_SEPARATOR;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_RENAME;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SECURITY;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_TABLESPACE;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_UNDEFINED;


public class MysqlSqlBuilder extends DefaultSqlBuilder {

    @Override
    public String quoteIdentifier(String identifier) {
        return quoteMysqlIdentifier(identifier);
    }

    @Override
    public String quoteQualifiedIdentifier(String... identifiers) {
        return Arrays.stream(identifiers)
                .filter(StringUtils::isNotBlank)
                .map(MysqlSqlBuilder::quoteMysqlIdentifier)
                .collect(Collectors.joining(SQLConstants.DOT));
    }

    @Override
    public String quoteAlias(String alias) {
        return quoteIdentifier(alias);
    }

    @Override
    public String buildCreateTable(Table table, TableBuilderConfig tableBuilderConfig) {
        StringBuilder script = new StringBuilder();
        script.append(SQLConstants.CREATE_TABLE_SQL_PREFIX);
        if (StringUtils.isNotBlank(table.getDatabaseName())) {
            script.append(quoteMysqlIdentifier(table.getDatabaseName())).append(SQLConstants.DOT);
        }
        script.append(quoteMysqlIdentifier(table.getName())).append(SQLConstants.SPACE_OPEN_PARENTHESIS).append(SQLConstants.LINE_SEPARATOR);
        for (TableColumn column : table.getColumnList()) {
            if (StringUtils.isBlank(column.getName()) || StringUtils.isBlank(column.getColumnType())) {
                continue;
            }
            MysqlColumnTypeEnum typeEnum = MysqlColumnTypeEnum.getByType(column.getColumnType());
            if (typeEnum == null) {
                continue;
            }
            script.append(SQLConstants.TAB).append(typeEnum.buildCreateColumnSql(column)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        List<TableIndex> indexList = table.getIndexList();
        if (CollectionUtils.isEmpty(indexList)) {
            indexList = List.of();
        }
        for (TableIndex tableIndex : indexList) {
            if (StringUtils.isBlank(tableIndex.getName()) || StringUtils.isBlank(tableIndex.getType())) {
                continue;
            }
            MysqlIndexTypeEnum mysqlIndexTypeEnum = MysqlIndexTypeEnum.getByType(tableIndex.getType());
            if (mysqlIndexTypeEnum == null) {
                continue;
            }
            script.append(SQLConstants.TAB).append(mysqlIndexTypeEnum.buildIndexScript(tableIndex)).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }


        script = new StringBuilder(script.substring(0, script.length() - 2));
        script.append(SQLConstants.LINE_SEPARATOR_CLOSE_PARENTHESIS);


        if (StringUtils.isNotBlank(table.getEngine())) {
            script.append(SQLConstants.ENGINE_SQL).append(MysqlSqlGuards.requireMysqlName(table.getEngine(), "engine"));
        }

        if (StringUtils.isNotBlank(table.getCharset())) {
            script.append(SQLConstants.DEFAULT_CHARACTER_SET_SQL).append(MysqlSqlGuards.requireMysqlName(table.getCharset(), "charset"));
        }

        if (StringUtils.isNotBlank(table.getCollate())) {
            script.append(SQLConstants.COLLATE_SQL).append(MysqlSqlGuards.requireMysqlName(table.getCollate(), "collation"));
        }

        if (table.getIncrementValue() != null) {
            script.append(SQLConstants.AUTO_INCREMENT_SQL).append(table.getIncrementValue());
        }

        if (StringUtils.isNotBlank(table.getComment())) {
            script.append(SQL_COMMENT_WITH_SINGLE_QUOTE).append(MysqlIdentifierProcessor.INSTANCE.escapeString(table.getComment())).append(SQLConstants.SINGLE_QUOTE);
        }

        if (StringUtils.isNotBlank(table.getPartition())) {
            script.append(SQL_PARTITION_SEPARATOR).append(table.getPartition());
        }
        if (StringUtils.isNotBlank(table.getTablespace())) {
            script.append(SQL_TABLESPACE).append(quoteMysqlIdentifier(table.getTablespace()));
        }
        script.append(SQLConstants.SEMICOLON);

        return script.toString();
    }

    @Override
    public String buildAITableSchema(Table table) {
        return buildCreateTable(table, TableBuilderConfig.defaultConfig());
    }


    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        StringBuilder tableBuilder = new StringBuilder();
        tableBuilder.append(SQLConstants.ALTER_TABLE_SQL_PREFIX);
        if (StringUtils.isNotBlank(oldTable.getDatabaseName())) {
            tableBuilder.append(quoteMysqlIdentifier(oldTable.getDatabaseName())).append(SQLConstants.DOT);
        }
        tableBuilder.append(quoteMysqlIdentifier(oldTable.getName())).append(SQLConstants.LINE_SEPARATOR);

        StringBuilder script = new StringBuilder();
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            script.append(SQLConstants.TAB).append(SQL_RENAME).append(quoteMysqlIdentifier(newTable.getName())).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append(SQLConstants.TAB).append(SQL_COMMENT).append(SQLConstants.SINGLE_QUOTE).append(MysqlIdentifierProcessor.INSTANCE.escapeString(newTable.getComment())).append(SQLConstants.SINGLE_QUOTE)
                    .append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getEngine(), newTable.getEngine()) && StringUtils.isNotBlank(newTable.getEngine())) {
            script.append(SQLConstants.TAB).append(SQL_ENGINE_ASSIGNMENT).append(MysqlSqlGuards.requireMysqlName(newTable.getEngine(), "engine")).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getCharset(), newTable.getCharset()) && StringUtils.isNotBlank(newTable.getCharset())) {
            script.append(SQLConstants.TAB).append(SQL_DEFAULT_CHARACTER_SET_ASSIGNMENT).append(MysqlSqlGuards.requireMysqlName(newTable.getCharset(), "charset")).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getCollate(), newTable.getCollate()) && StringUtils.isNotBlank(newTable.getCollate())) {
            script.append(SQLConstants.TAB).append(SQL_COLLATE_ASSIGNMENT).append(MysqlSqlGuards.requireMysqlName(newTable.getCollate(), "collation")).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        if (!Objects.equals(oldTable.getIncrementValue(), newTable.getIncrementValue())) {
            script.append(SQLConstants.TAB).append(SQL_AUTO_INCREMENT_ASSIGNMENT).append(newTable.getIncrementValue()).append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        if (StringUtils.isNotBlank(newTable.getTablespace())
                && !StringUtils.equalsIgnoreCase(oldTable.getTablespace(), newTable.getTablespace())) {
            script.append(SQLConstants.TAB).append(SQL_TABLESPACE)
                    .append(quoteMysqlIdentifier(newTable.getTablespace()))
                    .append(SQLConstants.COMMA_LINE_SEPARATOR);
        }
        List<TableColumn> addColumnList = new ArrayList<>();
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (tableColumn.getEditStatus() != null ? tableColumn.getEditStatus().equals(EditStatusEnum.ADD.name()) : false) {
                addColumnList.add(tableColumn);
            }
        }
        List<TableColumn> moveColumnList = movedElements(oldTable.getColumnList(), newTable.getColumnList());
        for (TableColumn tableColumn : newTable.getColumnList()) {
            boolean moved = moveColumnList.stream()
                    .anyMatch(oldColumn -> sameColumn(oldColumn, tableColumn));
            boolean added = addColumnList.contains(tableColumn);
            if ((StringUtils.isNotBlank(tableColumn.getEditStatus()) && StringUtils.isNotBlank(tableColumn.getColumnType())
                    && StringUtils.isNotBlank(tableColumn.getName())) || moved || added) {
                MysqlColumnTypeEnum typeEnum = MysqlColumnTypeEnum.getByType(tableColumn.getColumnType());
                if (typeEnum == null) {
                    continue;
                }
                if (moved || added) {
                    script.append(SQLConstants.TAB).append(typeEnum.buildModifyColumn(tableColumn, true, findPrevious(tableColumn, newTable)))
                            .append(SQLConstants.COMMA_LINE_SEPARATOR);
                } else {
                    script.append(SQLConstants.TAB).append(typeEnum.buildModifyColumn(tableColumn)).append(SQLConstants.COMMA_LINE_SEPARATOR);
                }
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus()) && StringUtils.isNotBlank(tableIndex.getType())) {
                MysqlIndexTypeEnum mysqlIndexTypeEnum = MysqlIndexTypeEnum.getByType(tableIndex.getType());
                if (mysqlIndexTypeEnum == null) {
                    continue;
                }
                script.append(SQLConstants.TAB).append(mysqlIndexTypeEnum.buildModifyIndex(tableIndex)).append(SQLConstants.COMMA_LINE_SEPARATOR);
            }
        }

        if (script.length() > 2) {
            script = new StringBuilder(script.substring(0, script.length() - 2));
            script.append(SQLConstants.SEMICOLON);
            return tableBuilder.append(script).toString();
        } else {
            return StringUtils.EMPTY;
        }

    }

    private String findPrevious(TableColumn tableColumn, Table newTable) {
        int index = newTable.getColumnList().indexOf(tableColumn);
        if (index == 0) {
            return PREVIOUS_COLUMN_NOT_FOUND;
        }
        for (int i = index - 1; i >= 0; i--) {
            if (newTable.getColumnList().get(i).getEditStatus() == null || !newTable.getColumnList().get(i).getEditStatus().equals(EditStatusEnum.DELETE.name())) {
                return newTable.getColumnList().get(i).getName();
            }
        }
        return PREVIOUS_COLUMN_NOT_FOUND;
    }

    @Override
    protected String appendSingleRowLimit(String operationType, String tableName, String whereClause, String sql) {
        return sql + SQL_LIMIT_ONE_SUFFIX;
    }

    @Override
    public String buildPageLimit(PageLimitRequest request) {
        String sql = request.getSql();
        int offset = request.getOffset();
        int pageNo = request.getPageNo();
        int pageSize = request.getPageSize();
        StringBuilder sqlBuilder = new StringBuilder(sql.length() + LIMIT_SQL_EXTRA_CAPACITY);
        sqlBuilder.append(sql);
        if (offset == 0) {
            sqlBuilder.append(SQLConstants.LINE_SEPARATOR_LIMIT_SQL);
            sqlBuilder.append(pageSize);
        } else {
            sqlBuilder.append(SQLConstants.LINE_SEPARATOR_LIMIT_SQL);
            sqlBuilder.append(offset);
            sqlBuilder.append(SQLConstants.COMMA);
            sqlBuilder.append(pageSize);
        }
        return sqlBuilder.toString();
    }


    @Override
    public String buildCreateDatabase(Database database) {
        StringBuilder sqlBuilder = new StringBuilder();
        sqlBuilder.append(SQLConstants.CREATE_DATABASE_SQL_PREFIX).append(quoteMysqlIdentifier(database.getName()));
        if (StringUtils.isNotBlank(database.getCharset())) {
            sqlBuilder.append(SQLConstants.DEFAULT_CHARACTER_SET_SQL).append(MysqlSqlGuards.requireMysqlName(database.getCharset(), "charset"));
        }
        if (StringUtils.isNotBlank(database.getCollation())) {
            sqlBuilder.append(SQLConstants.COLLATE_SQL).append(MysqlSqlGuards.requireMysqlName(database.getCollation(), "collation"));
        }
        return sqlBuilder.toString();
    }

    @Override
    public String buildDropDatabase(String databaseName) {
        return String.format(SQL_DROP_DATABASE_TEMPLATE, quoteMysqlIdentifier(databaseName));
    }

    @Override
    public ITablespaceSqlBuilder tablespace() {
        return new MysqlTablespaceSqlBuilder();
    }

    public static List<TableColumn> movedElements(List<TableColumn> original, List<TableColumn> modified) {
        int[][] dp = new int[original.size() + 1][modified.size() + 1];
        for (int i = 1; i <= original.size(); i++) {
            for (int j = 1; j <= modified.size(); j++) {
                if (sameColumn(original.get(i - 1), modified.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        List<TableColumn> moved = new ArrayList<>();
        int i = original.size();
        int j = modified.size();
        while (i > 0 && j > 0) {
            if (sameColumn(original.get(i - 1), modified.get(j - 1))) {
                i--;
                j--;
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                moved.add(original.get(i - 1));
                i--;
            } else {
                j--;
            }
        }
        while (i > 0) {
            moved.add(original.get(i - 1));
            i--;
        }

        return moved;
    }

    private static boolean sameColumn(TableColumn original, TableColumn modified) {
        String sourceName = StringUtils.defaultIfBlank(modified.getOldName(), modified.getName());
        return StringUtils.equals(original.getName(), sourceName);
    }

    public String buildGenerateReorderColumnSql(Table oldTable, Table newTable) {
        StringBuilder sql = new StringBuilder();
        int n = 0;
        Map<String, Integer> oldColumnIndexMap = new HashMap<>();
        for (int i = 0; i < oldTable.getColumnList().size(); i++) {
            oldColumnIndexMap.put(oldTable.getColumnList().get(i).getName(), i);
        }
        String[] oldColumnArray = oldTable.getColumnList().stream().map(TableColumn::getName).toArray(String[]::new);
        String[] newColumnArray = newTable.getColumnList().stream().map(TableColumn::getName).toArray(String[]::new);

        Set<String> oldColumnSet = new HashSet<>(Arrays.asList(oldColumnArray));
        Set<String> newColumnSet = new HashSet<>(Arrays.asList(newColumnArray));
        if (!oldColumnSet.equals(newColumnSet)) {
            return StringUtils.EMPTY;
        }

        buildSql(oldColumnArray, newColumnArray, sql, oldTable, newTable, n);

        return sql.toString();
    }

    private String[] buildSql(String[] originalArray, String[] targetArray, StringBuilder sql, Table oldTable, Table newTable, int n) {
        if (!originalArray[0].equals(targetArray[0])) {
            int a = findIndex(originalArray, targetArray[0]);
            TableColumn column = oldTable.getColumnList().stream().filter(col -> StringUtils.equals(col.getName(), originalArray[a])).findFirst().get();
            String[] newArray = moveElement(originalArray, a, 0, targetArray, new AtomicInteger(0));
            sql.append(SQL_MODIFY_COLUMN);
            MysqlColumnTypeEnum typeEnum = MysqlColumnTypeEnum.getByType(column.getColumnType());
            sql.append(typeEnum.buildColumn(column));
            sql.append(SQL_FIRST_TERMINATOR);
            n++;
            if (Arrays.equals(newArray, targetArray)) {
                return newArray;
            }
            String[] resultArray = buildSql(newArray, targetArray, sql, oldTable, newTable, n);
            if (Arrays.equals(resultArray, targetArray)) {
                return resultArray;
            }
        }
        int max = originalArray.length - 1;
        if (!originalArray[max].equals(targetArray[max])) {
            int a = findIndex(originalArray, targetArray[max]);
            TableColumn column = oldTable.getColumnList().stream().filter(col -> StringUtils.equals(col.getName(), originalArray[a])).findFirst().get();
            String[] newArray = moveElement(originalArray, a, max, targetArray, new AtomicInteger(0));
            if (n > 0) {
                sql.append(SQLConstants.ALTER_TABLE_SQL_PREFIX);
                if (StringUtils.isNotBlank(oldTable.getDatabaseName())) {
                    sql.append(quoteMysqlIdentifier(oldTable.getDatabaseName())).append(SQLConstants.DOT);
                }
                sql.append(quoteMysqlIdentifier(oldTable.getName())).append(SQLConstants.LINE_SEPARATOR);
            }
            sql.append(SQL_MODIFY_COLUMN);
            MysqlColumnTypeEnum typeEnum = MysqlColumnTypeEnum.getByType(column.getColumnType());
            sql.append(typeEnum.buildColumn(column));
            sql.append(SQL_AFTER);
            sql.append(quoteMysqlIdentifier(oldTable.getColumnList().get(max).getName()));
            sql.append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
            n++;
            if (Arrays.equals(newArray, targetArray)) {
                return newArray;
            }
            String[] resultArray = buildSql(newArray, targetArray, sql, oldTable, newTable, n);
            if (Arrays.equals(resultArray, targetArray)) {
                return resultArray;
            }
        }


        for (int i = 0; i < originalArray.length; i++) {
            int a = findIndex(targetArray, originalArray[i]);
            if (i != a && isMoveValid(originalArray, targetArray, i, a)) {
                int finalI = i;
                TableColumn column = oldTable.getColumnList().stream().filter(col -> StringUtils.equals(col.getName(), originalArray[finalI])).findFirst().get();
                if (n > 0) {
                    sql.append(SQLConstants.ALTER_TABLE_SQL_PREFIX);
                    if (StringUtils.isNotBlank(oldTable.getDatabaseName())) {
                        sql.append(quoteMysqlIdentifier(oldTable.getDatabaseName())).append(SQLConstants.DOT);
                    }
                    sql.append(quoteMysqlIdentifier(oldTable.getName())).append(SQLConstants.LINE_SEPARATOR);
                }
                sql.append(SQL_MODIFY_COLUMN);
                MysqlColumnTypeEnum typeEnum = MysqlColumnTypeEnum.getByType(column.getColumnType());
                sql.append(typeEnum.buildColumn(column));
                sql.append(SQL_AFTER);
                AtomicInteger continuousDataCount = new AtomicInteger(0);
                String[] newArray = moveElement(originalArray, i, a, targetArray, continuousDataCount);
                if (i < a) {
                    sql.append(quoteMysqlIdentifier(originalArray[a + continuousDataCount.get()]));
                } else {
                    sql.append(quoteMysqlIdentifier(originalArray[a - 1]));
                }

                sql.append(SQLConstants.SEMICOLON_LINE_SEPARATOR);
                n++;

                if (Arrays.equals(newArray, targetArray)) {
                    return newArray;
                }
                String[] resultArray = buildSql(newArray, targetArray, sql, oldTable, newTable, n);
                if (Arrays.equals(resultArray, targetArray)) {
                    return resultArray;
                }
            }
        }
        return originalArray;
    }

    private static int findIndex(String[] array, String element) {
        for (int i = 0; i < array.length; i++) {
            if (array[i].equals(element)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMoveValid(String[] originalArray, String[] targetArray, int i, int a) {
        return ((i == 0 || a == 0 || !originalArray[i - 1].equals(targetArray[a - 1])) &&
                (i >= originalArray.length - 1 || a >= targetArray.length - 1 || !originalArray[i + 1].equals(targetArray[a + 1])))
                || (i > 0 && a > 0 && !originalArray[i - 1].equals(targetArray[a - 1]));
    }

    private static String[] moveElement(String[] originalArray, int from, int to, String[] targetArray, AtomicInteger continuousDataCount) {
        String[] newArray = new String[originalArray.length];
        System.arraycopy(originalArray, 0, newArray, 0, originalArray.length);
        String temp = newArray[from];
        boolean isContinuousData = false;
        if (from < to) {
            for (int i = to; i < originalArray.length - 1; i++) {
                if (originalArray[i + 1].equals(targetArray[findIndex(targetArray, originalArray[i]) + 1])) {
                    continuousDataCount.set(continuousDataCount.incrementAndGet());
                } else {
                    break;
                }
            }
            if (continuousDataCount.get() > 0) {
                System.arraycopy(originalArray, from + 1, newArray, from, to - from + 1);
                isContinuousData = true;
            } else {
                System.arraycopy(originalArray, from + 1, newArray, from, to - from);
            }
        } else {
            System.arraycopy(originalArray, to, newArray, to + 1, from - to);
        }
        if (isContinuousData) {
            newArray[to + continuousDataCount.get()] = temp;
        } else {
            newArray[to] = temp;
        }
        return newArray;
    }


    @Override
    protected void buildTableName(String databaseName, String schemaName, String tableName, StringBuilder script) {
        if (StringUtils.isNotBlank(databaseName)) {
            script.append(quoteMysqlIdentifier(databaseName)).append(SQLConstants.DOT);
        }
        script.append(quoteMysqlIdentifier(tableName));
    }


    @Override
    protected void buildColumns(List<String> columnList, StringBuilder script) {
        if (CollectionUtils.isNotEmpty(columnList)) {
            script.append(SQLConstants.SPACE_OPEN_PARENTHESIS)
                    .append(columnList.stream().map(MysqlSqlBuilder::quoteMysqlIdentifier).collect(Collectors.joining(SQLConstants.COMMA)))
                    .append(SQLConstants.CLOSE_PARENTHESIS_SPACE);
        }
    }

    @Override
    public String buildCreateView(ModifyView modifyView) {
        StringBuilder createViewSqlBuilder = new StringBuilder(CREATE_VIEW_SQL_CAPACITY);
        createViewSqlBuilder.append(SQLConstants.CREATE_SQL_PREFIX);
        if (modifyView.isUseOrReplace()) {
            createViewSqlBuilder.append(SQLConstants.SQL_OR_REPLACE);
        }
        String algorithm = modifyView.getAlgorithm();
        if (StringUtils.isNotBlank(algorithm)) {
            createViewSqlBuilder.append(SQL_ALGORITHM).append(MysqlSqlGuards.requireEnumConstant(algorithm, MysqlViewAlgorithmOptionEnum.values(), "algorithm")).append(SQLConstants.SPACE);
        }
        String definer = modifyView.getDefiner();
        if (StringUtils.isNotBlank(definer)) {
            createViewSqlBuilder.append(SQL_DEFINER).append(MysqlSqlGuards.requireDefiner(definer)).append(SQLConstants.SPACE);
        }
        String security = modifyView.getSecurity();
        if (StringUtils.isNotBlank(security)) {
            createViewSqlBuilder.append(SQL_SECURITY).append(MysqlSqlGuards.requireEnumConstant(security, MysqlViewSqlSecurityOptionEnum.values(), "security")).append(SQLConstants.SPACE);
        }
        createViewSqlBuilder.append(SQLConstants.VIEW_KEYWORD);
        String databaseName = modifyView.getDatabaseName();
        if (StringUtils.isNotBlank(databaseName)) {
            createViewSqlBuilder.append(quoteMysqlIdentifier(databaseName)).append(SQLConstants.DOT);
        }
        String viewName = modifyView.getViewName();
        if (StringUtils.isNotBlank(viewName)) {
            createViewSqlBuilder.append(quoteMysqlIdentifier(viewName));
        } else {
            createViewSqlBuilder.append(SQL_UNDEFINED);
        }
        createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR_SQL_AS);
        String viewBody = modifyView.getViewBody();
        if (StringUtils.isNotBlank(viewBody)) {
            viewBody = viewBody.trim();
            if (viewBody.endsWith(SQLConstants.SEMICOLON)) {
                viewBody = viewBody.substring(0, viewBody.length() - 1);
            }
            createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR).append(viewBody).append(SQLConstants.SPACE);
        }
        String checkOption = modifyView.getCheckOption();
        if (StringUtils.isNotBlank(checkOption)) {
            createViewSqlBuilder.append(SQLConstants.LINE_SEPARATOR_SQL_WITH).append(MysqlSqlGuards.requireEnumConstant(checkOption, MysqlViewCheckOptionEnum.values(), "check option")).append(SQLConstants.CHECK_OPTION_SQL);
        }

        return createViewSqlBuilder + SQLConstants.SEMICOLON;
    }

    private static String quoteMysqlIdentifier(String name) {
        return MysqlIdentifierProcessor.INSTANCE.quoteIdentifierAlways(name);
    }

}
