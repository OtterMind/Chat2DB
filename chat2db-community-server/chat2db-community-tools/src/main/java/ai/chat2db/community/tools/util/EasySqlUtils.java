package ai.chat2db.community.tools.util;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.chat2db.community.tools.enums.OrderByDirectionEnum;
import ai.chat2db.community.tools.wrapper.param.OrderBy;
import com.baomidou.mybatisplus.core.conditions.ISqlSegment;
import com.baomidou.mybatisplus.core.conditions.segments.ColumnSegment;
import com.baomidou.mybatisplus.core.conditions.segments.OrderBySegmentList;
import com.baomidou.mybatisplus.core.enums.SqlKeyword;
import com.google.common.collect.Lists;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import static com.baomidou.mybatisplus.core.enums.SqlKeyword.ASC;
import static com.baomidou.mybatisplus.core.enums.SqlKeyword.DESC;


public class EasySqlUtils {

    private static final Pattern pattern = Pattern.compile(
            "\\bFROM\\s+((?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?(?:\\s*,\\s*(?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?)*)|" +
                    "\\bJOIN\\s+((?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?(?:\\s*,\\s*(?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?)*)|" +
                    "\\bEXISTS\\s*\\(\\s*SELECT.*?\\bFROM\\s+((?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?(?:\\s*,\\s*(?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?)*)|" +
                    "\\bINTO\\s+((?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?(?:\\s*,\\s*(?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?)*)|" +
                    "\\bUPDATE\\s+((?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?(?:\\s*,\\s*(?:[\\w\\.]+|`[\\w\\.]+`)(?:\\s+AS\\s+\\w+|\\s+\\w+)?)*)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);


    private static final Pattern sqlPattern = Pattern.compile("(?i)select\\s+FROM");

    public static final String DATABASE_NAME = "databaseName";

    public static final String SCHEMA_NAME = "schemaName";

    public static final String TABLE_NAME = "tableName";

    public static final String TABLE_ALIAS_MAP = "tableAliasMap";

    public static final String DATABASE_OR_SCHEMA_NAME = "databaseOrSchemaName";

    public static String orderBy(List<OrderBy> orderByList) {
        if (CollectionUtils.isEmpty(orderByList)) {
            return null;
        }
        OrderBySegmentList orderBySegmentList = new OrderBySegmentList();
        for (OrderBy orderBy : orderByList) {
            orderBySegmentList.addAll(
                Arrays.asList(SqlKeyword.ORDER_BY, columnToSqlSegment(orderBy.getOrderConditionName()),
                    parseOrderBy(orderBy.getDirection())));
        }
        return orderBySegmentList.getSqlSegment();
    }


    public static ColumnSegment columnToSqlSegment(String column) {
        return () -> column;
    }

    public static ISqlSegment parseOrderBy(OrderByDirectionEnum direction) {
        if (direction == OrderByDirectionEnum.ASC) {
            return ASC;
        }
        return DESC;
    }

    public static String buildLikeRightFuzzy(String param) {
        if (param == null) {
            return null;
        }
        return param + "%";
    }

    public static Set<String> parseTableNames(String sql, StringBuilder stringBuilder) {
        Set<String> tables = new HashSet<>();
        try {
            Matcher matcher = pattern.matcher(sql);
            while (matcher.find()) {
                for (int i = 1; i <= matcher.groupCount(); i++) {
                    if (matcher.group(i) != null) {
                        String tableName = matcher.group(i);
                        if (tableName.contains(".")) {
                            tableName = tableName.substring(tableName.lastIndexOf(".") + 1);
                        }
                        tables.add(tableName);
                    }
                }
            }
        } catch (Exception e) {
            stringBuilder.append(e.getMessage());
        }
        return tables;
    }

    public static Map<String, Object> parseTableSchema(String sql, StringBuilder stringBuilder) {
        sql = sql.replaceAll("['\"`]", "");
        Map<String, Object> tableSchemaMap = new HashMap<>();
        List<String> databaseList = Lists.newArrayList();
        tableSchemaMap.put(DATABASE_NAME, databaseList);
        List<String> schemaList = Lists.newArrayList();
        tableSchemaMap.put(SCHEMA_NAME, schemaList);
        List<String> databaseOrSchemaList = Lists.newArrayList();
        tableSchemaMap.put(DATABASE_OR_SCHEMA_NAME, databaseOrSchemaList);
        List<String> tableList = Lists.newArrayList();
        tableSchemaMap.put(TABLE_NAME, tableList);
        Map<String, String> tableAliases = new HashMap<>();
        tableSchemaMap.put(TABLE_ALIAS_MAP, tableAliases);
        try {
            Matcher matcher = pattern.matcher(sql);
            while (matcher.find()) {
                String[] matchParts = matcher.group().split("\\s+", 2);
                String matchPart = matchParts.length > 1 ? matchParts[1] : matchParts[0];
                String[] tables = matchPart.split("\\s*,\\s*");
                for (String table : tables) {
                    String[] tableParts = table.split("\\s+");
                    String tableName = tableParts[0];
                    String tableAlias = null;
                    if (tableParts.length > 2 && "AS".equalsIgnoreCase(tableParts[1])) {
                        tableAlias = tableParts[2];
                    } else if (tableParts.length > 1 && !"AS".equalsIgnoreCase(tableParts[1])) {
                        tableAlias = tableParts[1];
                    }
                    if (!tableName.contains(".")) {
                        tableName = tableName.replaceAll("^['\"`]+|['\"`]+$", "");
                        tableList.add(tableName);
                        if (StringUtils.isNotBlank(tableAlias)) {
                            tableAliases.put(tableAlias, tableName);
                        }
                    } else {
                        String[] parts = tableName.split("\\.");
                        String serverName = null, databaseName = null, schemaName = null, actualTableName = null;
                        switch (parts.length) {
                            case 4:
                                serverName = parts[0];
                                databaseName = parts[1];
                                schemaName = parts[2];
                                actualTableName = parts[3];
                                databaseList.add(databaseName);
                                schemaList.add(schemaName);
                                break;
                            case 3:
                                databaseName = parts[0];
                                schemaName = parts[1];
                                actualTableName = parts[2];
                                databaseList.add(databaseName);
                                schemaList.add(schemaName);
                                break;
                            case 2:
                                schemaName = parts[0];
                                actualTableName = parts[1];
                                databaseOrSchemaList.add(schemaName);
                                break;
                            case 1:
                                actualTableName = parts[0];
                                break;
                        }
                        actualTableName = actualTableName.replaceAll("^['\"`]+|['\"`]+$", "");
                        tableList.add(actualTableName);
                        if (StringUtils.isNotBlank(tableAlias)) {
                            tableAliases.put(tableAlias, tableName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            stringBuilder.append(e.getMessage());
        }
        return tableSchemaMap;
    }


    public static Map<String, Object> extractTableSchemaInfo(String sql, StringBuilder stringBuilder) {
        Matcher matcher = sqlPattern.matcher(sql);
        sql = matcher.replaceAll("SELECT * FROM");
        Map<String, Object> tableSchemaMap = new HashMap<>();
        List<String> databaseList = Lists.newArrayList();
        tableSchemaMap.put(DATABASE_NAME, databaseList);
        List<String> schemaList = Lists.newArrayList();
        tableSchemaMap.put(SCHEMA_NAME, schemaList);
        List<String> tableList = Lists.newArrayList();
        tableSchemaMap.put(TABLE_NAME, tableList);
        Map<String, String> tableAliases = new HashMap<>();
        tableSchemaMap.put(TABLE_ALIAS_MAP, tableAliases);
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder tablesNamesFinder = new TablesNamesFinder() {
                @Override
                public void visit(Table table) {
                    super.visit(table);
                    String schemaName = table.getSchemaName();
                    if (Objects.nonNull(schemaName)) {
                        schemaList.add(schemaName);
                    }
                    if (Objects.nonNull(table.getDatabase()) && StringUtils.isNotBlank(table.getDatabase().getDatabaseName())) {
                        schemaName = table.getDatabase().getDatabaseName();
                        databaseList.add(schemaName);
                    }
                    tableList.add(table.getName().replaceAll("^`+|`+$", ""));
                    if (table.getAlias() != null) {
                        tableAliases.put(table.getAlias().getName(), table.getName().replaceAll("^`+|`+$", ""));
                    }
                }
            };
            tablesNamesFinder.getTableList(statement);
        } catch (Exception e) {
            String error = "SQL解析数据库失败,请检查SQL语句是否正确" + e;
            stringBuilder.append(error);
        }
        return tableSchemaMap;
    }

}
