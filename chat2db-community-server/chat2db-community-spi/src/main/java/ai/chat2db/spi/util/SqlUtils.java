
package ai.chat2db.spi.util;

import ai.chat2db.spi.constant.SqlValueConstants;
import ai.chat2db.spi.DefaultSqlSyntaxHandler;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.IdentifierTypeEnum;
import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;
import ai.chat2db.community.domain.api.model.parser.info.ColumnInfo;
import ai.chat2db.community.domain.api.model.parser.info.TableInfo;
import ai.chat2db.community.domain.api.model.parser.token.Identifier;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.spi.lineage.JSqlParserLineageFinder;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.sql.RefreshTarget;
import ai.chat2db.community.domain.api.model.sql.SimpleSqlStatement;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.procedure.CreateProcedure;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.select.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Slf4j
public class SqlUtils {

    public static void buildCanEditResult(String sql, DbType dbType, ExecuteResponse executeResult) {
        try {
            Statement statement;
            if (DbType.sqlserver.equals(dbType)) {
                statement = CCJSqlParserUtil.parse(sql, ccjSqlParser -> ccjSqlParser.withSquareBracketQuotation(true));
            } else {
                statement = CCJSqlParserUtil.parse(sql);
            }
            if (statement instanceof Select) {
                Select select = (Select) statement;
                PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
                if (plainSelect.getJoins() == null && plainSelect.getFromItem() != null) {
                    for (SelectItem item : plainSelect.getSelectItems()) {
                        if (item.getAlias() != null) {
                            executeResult.setCanEdit(false);
                            return;
                        }
                        if (item.getExpression() instanceof Function) {
                            Function function = (Function) item.getExpression();
                            if ("COUNT".equalsIgnoreCase(function.getName())) {
                                executeResult.setCanEdit(false);
                                return;
                            }
                        }
                    }
                    executeResult.setCanEdit(true);
                    SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, dbType);
                    if ((sqlStatement instanceof SQLSelectStatement sqlSelectStatement)) {
                        SQLExprTableSource sqlExprTableSource = (SQLExprTableSource) getSQLExprTableSource(
                                sqlSelectStatement.getSelect().getFirstQueryBlock().getFrom());
                        // A derived table (subquery in FROM) or other non-expr/non-join table
                        // source cannot be mapped to a single physical table; without this guard
                        // tableName stays null and the edit flow would emit invalid SQL such as
                        // "UPDATE null SET ...". getSQLExprTableSource returns null for those
                        // cases (see below), so keep the result set non-editable, mirroring the
                        // null-check already present in getTableName(). Follows the same
                        // setCanEdit(false)+return pattern used above for aliased/COUNT columns.
                        if (sqlExprTableSource == null) {
                            executeResult.setCanEdit(false);
                            return;
                        }
                        executeResult.setTableName(getMetaDataTableName(sqlExprTableSource.getCatalog(), sqlExprTableSource.getSchema(), sqlExprTableSource.getTableName()));
                    }
                } else {
                    executeResult.setCanEdit(false);
                }
            }
        } catch (Exception e) {
            log.error("buildCanEditResult error", e);
            executeResult.setCanEdit(false);
        }
    }

    public static String extractTableName(String sql) {
        String tableName = null;
        if (StringUtils.isBlank(sql)) {
            return tableName;
        }
        sql = sql.trim();
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.getStatements().size() > 0) {
                Statement statement = statements.getStatements().get(0);
                if (statement instanceof CreateTable) {
                    CreateTable createTable = (CreateTable) statement;
                    tableName = createTable.getTable().getName();
                    return removeTableSymbol(tableName);
                }
            }
        } catch (Exception e) {
            log.error("getTableName error", e);
        }
        if (tableName == null) {
            tableName = extractTableNameByPattern(sql);
        }
        return tableName;
    }


    public static String extractTableNameByPattern(String sqlQuery) {
        String regex = "CREATE\\s+TABLE\\s+([\"\\[`]?(?:[a-zA-Z0-9_\\s]+)[\"\\]`]?)\\s*\\(";
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sqlQuery);

        if (matcher.find()) {
            String tableName = matcher.group(1);
            tableName = removeTableSymbol(tableName);
            return tableName;
        } else {
            return null;
        }
    }

    private static String removeTableSymbol(String name) {
        if (StringUtils.isBlank(name)) {
            return name;
        } else {
            return name.replaceAll("[\"\\[\\]`]", "");
        }
    }

    private static String getMetaDataTableName(String... names) {
        return Arrays.stream(names).filter(name -> StringUtils.isNotBlank(name)).map(name -> name).collect(Collectors.joining("."));
    }

    public static String formatSQLString(Object para) {
        return para != null ? " '" + para + "' " : null;
    }

    public static String getTableName(String sql, DbType dbType) {
        SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, dbType);
        if (!(sqlStatement instanceof SQLSelectStatement sqlSelectStatement)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        SQLExprTableSource sqlExprTableSource = (SQLExprTableSource) getSQLExprTableSource(
                sqlSelectStatement.getSelect().getFirstQueryBlock().getFrom());
        if (sqlExprTableSource == null) {
            return SqlValueConstants.DEFAULT_TABLE_NAME;
        }
        return sqlExprTableSource.getTableName();
    }

    private static SQLTableSource getSQLExprTableSource(SQLTableSource sqlTableSource) {
        if (sqlTableSource instanceof SQLExprTableSource sqlExprTableSource) {
            return sqlExprTableSource;
        } else if (sqlTableSource instanceof SQLJoinTableSource sqlJoinTableSource) {
            return getSQLExprTableSource(sqlJoinTableSource.getLeft());
        }
        return null;
    }

    private static final String DELIMITER_AFTER_REGEX = "^\\s*(?i)delimiter\\s+(\\S+)";
    private static final String DELIMITER_REGEX = "(?mi)^\\s*delimiter\\s*;?";

    private static final String EVENT_REGEX = "(?i)\\bcreate\\s+event\\b.*?\\bend\\b";

    public static List<String> parse(String sql, DbType dbType, boolean removeComment) {
        List<String> list = new ArrayList<>();
        try {
            if (StringUtils.isBlank(sql)) {
                return list;
            }
            if (removeComment) {
                sql = SQLParserUtils.removeComment(sql, dbType);
            }
            try {
                if (DbType.oracle.equals(dbType)) {
                    List<ai.chat2db.community.domain.api.model.parser.statement.Statement> statements = DefaultSqlSyntaxHandler.simpleParserStatements(sql, DatabaseTypeEnum.ORACLE.name());
                    return statements.stream().map(ai.chat2db.community.domain.api.model.parser.statement.Statement::getSql).toList();
                }
            } catch (Exception e) {
                log.error("sqlSplitter error", e);
            }
            try {
                if (DbType.mysql.equals(dbType) ||
                        DbType.mariadb.equals(dbType) ||
                        DbType.oceanbase.equals(dbType)) {
                    sql = updateNow(sql, dbType);
                    SqlSplitProcessor sqlSplitProcessor = new SqlSplitProcessor(dbType, true, true);
                    sqlSplitProcessor.setDelimiter(";");
                    return split(sqlSplitProcessor, sql, dbType, removeComment);
                }
            } catch (Exception e) {
                log.error("sqlSplitProcessor error", e);
            }
            if (StringUtils.isBlank(sql)) {
                return list;
            }
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            for (Statement stmt : statements.getStatements()) {
                if (!(stmt instanceof CreateProcedure)) {
                    list.add(stmt.toString());
                }
            }
            if (CollectionUtils.isEmpty(list)) {
                list.add(sql);
            }
        } catch (Exception e) {
            try {
                return splitWithCreateEvent(sql, dbType);
            } catch (Exception e1) {
                if (removeComment) {
                    return SQLParserUtils.splitAndRemoveComment(sql, dbType);
                }
                {
                    return SQLParserUtils.split(sql, dbType);
                }
            }
        }
        return list;
    }

    private static String removeDelimiter(String str) {
        try {
            if (str.toUpperCase().contains("DELIMITER")) {
                Pattern pattern = Pattern.compile(DELIMITER_AFTER_REGEX, Pattern.MULTILINE);
                Matcher matcher = pattern.matcher(str);
                while (matcher.find()) {
                    String mm = matcher.group(1);
                    if (!";".equals(mm)) {
                        str = str.replace(mm, "");
                    }
                }
            }
            return str.replaceAll(DELIMITER_REGEX, "");
        } catch (Exception e) {
            return str;
        }
    }

    private static List<String> splitWithCreateEvent(String str, DbType dbType) {
        List<String> list = new ArrayList<>();
        String sql = SQLParserUtils.removeComment(str, dbType).trim();
        Pattern pattern = Pattern.compile(EVENT_REGEX, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);
        StringBuilder stringBuilder = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                List<String> l = SQLParserUtils.split(sql.substring(lastEnd, matcher.start()), dbType);
                list.addAll(l);
            }
            list.add(matcher.group());
            lastEnd = matcher.end();
        }
        if (lastEnd < sql.length()) {
            List<String> l = SQLParserUtils.split(sql.substring(lastEnd), dbType);
            list.addAll(l);
        }
        return list;
    }


    private static String updateNow(String sql, DbType dbType) {
        if (StringUtils.isBlank(sql) || !DbType.mysql.equals(dbType)) {
            return sql;
        }
        if (sql.contains("default now()")) {
            return sql.replace("default now()", "default CURRENT_TIMESTAMP");
        }
        if (sql.contains("DEFAULT now()")) {
            return sql.replace("DEFAULT now()", "default CURRENT_TIMESTAMP");
        }
        if (sql.contains("default now ()")) {
            return sql.replace("default now ()", "default CURRENT_TIMESTAMP");
        }
        if (sql.contains("DEFAULT now ()")) {
            return sql.replace("DEFAULT now ()", "DEFAULT CURRENT_TIMESTAMP");
        }
        return sql;
    }

    public static String getSqlValue(String value, String dataType) {
        if (value == null) {
            return null;
        }
        if ("".equals(value)) {
            return "''";
        }
        if (SqlValueConstants.DEFAULT_VALUE.equals(value)) {
            return "DEFAULT";
        }
        DataTypeEnum dataTypeEnum = DataTypeEnum.getByCode(dataType);
        return dataTypeEnum.getSqlValue(value);
    }


    public static boolean hasPageLimit(String sql, DbType dbType) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select) {
                Select selectStatement = (Select) statement;
                PlainSelect selectBody = selectStatement.getPlainSelect();
                if (selectBody instanceof PlainSelect) {
                    PlainSelect plainSelect = (PlainSelect) selectBody;
                    if (plainSelect.getLimit() != null || plainSelect.getOffset() != null || plainSelect.getTop() != null || plainSelect.getFetch() != null) {
                        return true;
                    }
                    if (DbType.oracle.equals(dbType)) {
                        return sql.contains("ROWNUM") || sql.contains("rownum");
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }


    public static String stripTrailingSemicolon(String sql) {
        if (sql == null) {
            return null;
        }
        int end = sql.length();
        while (end > 0) {
            char c = sql.charAt(end - 1);
            if (Character.isWhitespace(c) || c == ';') {
                end--;
            } else {
                break;
            }
        }
        return sql.substring(0, end);
    }

    private static List<String> split(SqlSplitProcessor processor, String sql, DbType dbType, boolean removeComment) {
        StringBuffer buffer = new StringBuffer();
        List<SplitSqlString> sqls = processor.split(buffer, sql);
        String bufferStr = buffer.toString();
        if (bufferStr.trim().length() != 0) {
            int lastSqlOffset;
            if (sqls.size() == 0) {
                int index = sql.indexOf(bufferStr.trim(), 0);
                lastSqlOffset = index == -1 ? 0 : index;
            } else {
                int from = sqls.get(sqls.size() - 1).getOffset() + sqls.get(sqls.size() - 1).getStr().length();
                int index = sql.indexOf(bufferStr.trim(), from);
                lastSqlOffset = index == -1 ? from : index;
            }
            sqls.add(new SplitSqlString(lastSqlOffset, bufferStr));
        }
        return sqls.stream().map(splitSqlString -> removeComment ? SQLParserUtils.removeComment(splitSqlString.getStr(), dbType) : splitSqlString.getStr()).collect(Collectors.toList());
    }

    public static String quoteObjectName(String name) {
        return quoteObjectName(name, "\"");
    }

    public static String quoteObjectName(String name, String quoteSymbol) {
        if (StringUtils.isNotBlank(name)) {
            boolean startsWithQuote = name.startsWith(quoteSymbol);
            boolean endsWithQuote = name.endsWith(quoteSymbol);

            if (!startsWithQuote && !endsWithQuote) {
                return quoteSymbol + name + quoteSymbol;
            } else if (startsWithQuote && !endsWithQuote) {
                return quoteSymbol + quoteSymbol + name + quoteSymbol;
            } else if (!startsWithQuote) {
                return quoteSymbol + name + quoteSymbol + quoteSymbol;
            }
            return name;
        }
        return name;
    }


    public static String removeDigits(String input) {
        if (StringUtils.isBlank(input)) {
            return input;
        }
        return input.replaceAll("\\(\\d+\\)", "");
    }

    public static String count(String sql, String dataBaseType) {
        if (StringUtils.isBlank(sql)) {
            return sql;
        }
        String s = sql.trim();
        try {
            String countSql = SqlGenerateUtil.generateSelectCountSql(sql, dataBaseType);
            return trimTrailingSemicolon(countSql);
        } catch (Exception e) {
            log.error("druid parser sql error,sql:" + sql, e);
            if (!s.toLowerCase().startsWith("select")) {
                return null;
            }
            s = trimTrailingSemicolon(s);
            return "SELECT COUNT(*) FROM (" + s + ") chat2db_count_temp_table";
        }
    }

    static String trimTrailingSemicolon(String sql) {
        if (sql.endsWith(";")) {
            return sql.substring(0, sql.length() - 1);
        }
        return sql;
    }

    public static List<SimpleSqlStatement> parseStatements(String script, DbType dbType, String type) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        Long dataSourceId = connectInfo.getDataSourceId();
        try {
            List<ai.chat2db.community.domain.api.model.parser.statement.Statement> statements = DefaultSqlSyntaxHandler.simpleParserStatements(script, type);
            if (CollectionUtils.isNotEmpty(statements)) {
                return statements.stream().map(statement -> {
                    SimpleSqlStatement simpleSqlStatement = new SimpleSqlStatement();
                    simpleSqlStatement.setComment(statement.getComment());
                    simpleSqlStatement.setSql(statement.getSql());
                    simpleSqlStatement.setSqlType(statement.getType());
                    List<Identifier> identifiers = statement.getIdentifiers();
                    if (CollectionUtils.isNotEmpty(identifiers)) {
                        List<RefreshTarget> refreshTargets = new ArrayList<>(identifiers.size());
                        for (Identifier identifier : identifiers) {
                            RefreshTarget refreshTarget = new RefreshTarget();
                            refreshTarget.setDatabaseName(identifier.getIdentifierDatabase());
                            refreshTarget.setSchemaName(identifier.getIdentifierSchema());
                            refreshTarget.setDataSourceId(dataSourceId);
                            refreshTargets.add(refreshTarget);
                        }
                        simpleSqlStatement.setRefreshTargets(refreshTargets);
                    } else {
                        simpleSqlStatement.setRefreshTargets(Collections.emptyList());
                    }
                    return simpleSqlStatement;
                }).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.error("parse statements error,sql:" + script, e);
        }
        List<String> sqls = parse(script, dbType, true);
        return sqls.stream().map(SimpleSqlStatement::new).toList();
    }

    public static List<SimpleSqlStatement> parseAndValidTableStatements(String script, String type) {
        DbType dataBaseType = JdbcUtils.parse2DruidDbType(type);
        return parseAndValidTableStatements(script, dataBaseType, type);
    }

    public static List<SimpleSqlStatement> parseAndValidTableStatements(String script, DbType dbType, String type) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        Long dataSourceId = connectInfo.getDataSourceId();
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        JSqlParserLineageFinder.DatabaseConfig databaseConfig = JSqlParserLineageFinder.DatabaseConfig.builder()
                .databaseType(type)
                .supportDatabase(dbConfig.isSupportDatabase())
                .supportSchema(dbConfig.isSupportSchema())
                .build();
        List<SimpleSqlStatement> simpleSqlStatements = new ArrayList<>();
        try {
            List<ai.chat2db.community.domain.api.model.parser.statement.Statement> statements = DefaultSqlSyntaxHandler.validTableStatements(script, type);
            if (CollectionUtils.isNotEmpty(statements)) {
                return convertStatementsToSimple(statements, dataSourceId);
            }
        } catch (Exception e) {
            log.error("parse statements error");
        }

        for (String sql : parse(script, dbType, true)) {
            try {
                List<ai.chat2db.community.domain.api.model.parser.statement.Statement> statements = DefaultSqlSyntaxHandler.validTableStatements(sql, type);
                if (CollectionUtils.isEmpty(statements)) {
                    simpleSqlStatements.add(new SimpleSqlStatement(sql));
                } else {
                    simpleSqlStatements.addAll(convertStatementsToSimple(statements, dataSourceId));
                }
            } catch (Exception e) {
                try {
                    SimpleSqlStatement simpleSqlStatement = JSqlParserLineageFinder.findLineage(sql, databaseConfig);
                    simpleSqlStatements.add(simpleSqlStatement);
                } catch (Exception ex) {
                    log.error(" jsqlparser find lineage failed");
                    simpleSqlStatements.add(new SimpleSqlStatement(sql));
                }
            }
        }
        return simpleSqlStatements;
    }

    private static List<SimpleSqlStatement> convertStatementsToSimple(List<ai.chat2db.community.domain.api.model.parser.statement.Statement> statements, Long dataSourceId) {
        return statements.stream().map(statement -> {
            SimpleSqlStatement simpleSqlStatement = new SimpleSqlStatement();
            simpleSqlStatement.setComment(statement.getComment());
            simpleSqlStatement.setSql(statement.getSql());
            String type = statement.getType();
            simpleSqlStatement.setSqlType(type);
            if (type.equals(SqlTypeEnum.SELECT.name())) {
                Map<TableInfo, String> tableAliasMap = statement.getTableAliasMap();
                Map<ColumnInfo, String> columnAliasMap = statement.getColumnAliasMap();
                if (MapUtils.isEmpty(tableAliasMap)) {
                    simpleSqlStatement.setTables(null);
                }
                List<SimpleSqlStatement.SimpleTable> simpleTables = constructTables(tableAliasMap, columnAliasMap, dataSourceId);
                simpleSqlStatement.setTables(simpleTables);
            } else {
                List<Identifier> identifiers = statement.getIdentifiers();
                if (CollectionUtils.isNotEmpty(identifiers)) {
                    List<SimpleSqlStatement.SimpleTable> simpleTables = identifiers.stream()
                            .filter(identifier -> IdentifierTypeEnum.TABLE.name().equals(identifier.getIdentifierType()))
                            .map(identifier -> {
                                SimpleSqlStatement.SimpleTable simpleTable = new SimpleSqlStatement.SimpleTable();
                                simpleTable.setTableName(identifier.getIdentifierName());
                                simpleTable.setDatabaseName(identifier.getIdentifierDatabase());
                                simpleTable.setSchemaName(identifier.getIdentifierSchema());
                                simpleTable.setDatasourceId(dataSourceId);
                                simpleTable.setColumns(null);
                                return simpleTable;
                            })
                            .toList();
                    simpleSqlStatement.setTables(simpleTables);
                }
            }
            return simpleSqlStatement;
        }).collect(Collectors.toList());
    }

    private static List<SimpleSqlStatement.SimpleTable> constructTables(Map<TableInfo, String> tableAliasMap, Map<ColumnInfo, String> columnAliasMap, Long datasourceId) {
        List<SimpleSqlStatement.SimpleTable> tables = new ArrayList<>();
        for (Map.Entry<TableInfo, String> tableEntry : tableAliasMap.entrySet()) {
            TableInfo tableInfo = tableEntry.getKey();
            String tableAlias = tableEntry.getValue();
            SimpleSqlStatement.SimpleTable simpleTable = new SimpleSqlStatement.SimpleTable(
                    tableInfo.getDatabase(),
                    tableInfo.getSchema(),
                    tableInfo.getTable(),
                    datasourceId,
                    tableAlias,
                    new ArrayList<>()
            );
            if (MapUtils.isEmpty(columnAliasMap)) {
                simpleTable.setColumns(null);
            } else {
                List<SimpleSqlStatement.Column> columns = columnAliasMap.entrySet().stream()
                        .filter(entry -> {
                            ColumnInfo columnInfo = entry.getKey();
                            return StringUtils.isBlank(columnInfo.getTable())
                                    || columnInfo.getTable().equalsIgnoreCase(tableInfo.getTable())
                                    || columnInfo.getTable().equalsIgnoreCase(tableAlias);
                        })
                        .map(entry -> {
                            ColumnInfo columnInfo = entry.getKey();
                            String columnAlias = entry.getValue();
                            return new SimpleSqlStatement.Column(tableInfo.getTable(), columnInfo.getColumn(), columnAlias);
                        })
                        .collect(Collectors.toList());
                simpleTable.setColumns(columns);
            }
            tables.add(simpleTable);
        }

        return tables;
    }


    public static String getInheritedType(String type) {
        if ("DM".equalsIgnoreCase(type) || "OCEANBASE_ORACLE".equalsIgnoreCase(type)
                || "XUGUDB".equalsIgnoreCase(type) || "SUNDB".equalsIgnoreCase(type)) {
            return "ORACLE";
        }
        if ("KINGBASE".equalsIgnoreCase(type) || "OPENGAUSS".equalsIgnoreCase(type) || "GAUSSDB".equalsIgnoreCase(type)) {
            return "POSTGRESQL";
        }

        if ("DORIS".equalsIgnoreCase(type)) {
            return "MYSQL";
        }
        return type;
    }

    public static String result2Markdown(ExecuteResponse result) {
        if (result == null) {
            return "";
        }
        if (CollectionUtils.isEmpty(result.getHeaderList()) || CollectionUtils.isEmpty(result.getDataList())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("|");
        for (Header header : result.getHeaderList()) {
            sb.append(header.getName()).append("|");
        }
        for (List<String> data : result.getDisplayDataList()) {
            sb.append("\n|");
            for (String d : data) {
                sb.append(d).append("|");
            }
        }
        return sb.toString();
    }

}
