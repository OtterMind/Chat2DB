package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.spi.DefaultSqlSyntaxHandler;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.completion.result.SqlCompletionResponse;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionCandidateTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.console.ConsoleParserCache;
import ai.chat2db.community.domain.api.model.db.TableNode;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlCompletionGetRequest;
import ai.chat2db.community.domain.api.model.request.db.DbTableQueryRequest;
import ai.chat2db.community.domain.api.service.db.IDbTableService;
import ai.chat2db.community.domain.core.cache.MemoryCacheManage;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.ISQLIdentifierProcessor;
import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.ForeignKeyInfo;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.ColumnMetadataRequest;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.model.request.TablesRequest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import static ai.chat2db.community.domain.core.cache.CacheKey.getColumnKey;
import static ai.chat2db.community.domain.core.cache.CacheKey.getConsoleParserKey;
import static ai.chat2db.community.domain.core.cache.CacheKey.getTableKey;
import static ai.chat2db.spi.constant.SqlCompletionPattern.DELETE_FROM_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.END_PERIOD_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.FIRST_WORD_FROM_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.FIRST_WORD_SELECT_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.INSERT_IGNORE_INTO_COLUMN_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.INSERT_IGNORE_INTO_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.INSERT_INTO_COLUMN_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.INSERT_INTO_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.KEYWORD_BEFORE_PERIOD_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.LAST_WORD_JOIN_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.LAST_WORD_WHERE_OR_AND_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.REPLACE_INTO_COLUMN_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.REPLACE_INTO_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.SELECT_COUNT_FROM_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.SELECT_FROM_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.SELECT_STAR_FROM_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.SELECT_TABLE_NAME_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.SELECT_TIP_COLUMN_FROM_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.STARTS_WITH_SPACE_NEWLINE_SEMICOLON_OR_RIGHT_PARENTHESIS;
import static ai.chat2db.spi.constant.SqlCompletionPattern.UPDATE_SET_COLUMN_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.UPDATE_TABLE_PATTERN;
import static ai.chat2db.spi.constant.SqlCompletionPattern.extractInsertIntoTableColumns;
import static ai.chat2db.spi.constant.SqlCompletionPattern.extractTableNameAndAlias;
import static ai.chat2db.spi.constant.SqlCompletionPattern.extractTableNamesWithPattern;
import static ai.chat2db.spi.constant.SqlCompletionPattern.matchesAnyPattern;
import static ai.chat2db.spi.constant.SqlCompletionPattern.matchesPattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class GenericSqlCompletionEngine {

    private final IDbTableService tableService;

    public SqlCompletionResponse complete(DbSqlCompletionGetRequest param) {
        if (param == null) {
            return SqlCompletionResponse.rejected("sql.completion.param.null");
        }
        int cursor = resolveCursor(param);
        try {
            List<SqlCompletionCandidate> candidates = buildCandidates(param, cursor);
            if (CollectionUtils.isEmpty(candidates)) {
                return SqlCompletionResponse.empty();
            }
            return SqlCompletionResponse.success(cursor, cursor, candidates);
        } catch (Exception e) {
            log.error("generic sql completion error", e);
            return SqlCompletionResponse.empty();
        }
    }

    private List<SqlCompletionCandidate> buildCandidates(DbSqlCompletionGetRequest param, int cursor) {
        DBConfig dbConfig = Chat2DBContext.getDBConfig();
        if (dbConfig == null || StringUtils.isBlank(dbConfig.getDbType())) {
            return List.of();
        }
        String dbType = dbConfig.getDbType().toUpperCase();
        if (StringUtils.equalsAnyIgnoreCase(dbType, DatabaseTypeEnum.MONGODB.name(), DatabaseTypeEnum.REDIS.name())) {
            return List.of();
        }
        boolean supportDatabase = dbConfig.isSupportDatabase();
        boolean supportSchema = dbConfig.isSupportSchema();
        String databaseName = param.getDatabaseName();
        String schemaName = param.getSchemaName();
        Long consoleId = param.getConsoleId();
        Long dataSourceId = param.getDataSourceId();
        String consoleParserKey = getConsoleParserKey(dataSourceId, consoleId);
        ConsoleParserCache consoleParserCache = MemoryCacheManage.computeIfAbsent(consoleParserKey, ConsoleParserCache::new);
        String sql = Objects.toString(param.getSql(), "");
        String originalBeforeSql = sql.substring(0, cursor);
        String beforeSql = DefaultSqlSyntaxHandler.buildSqlByKeywordsBeforeCursor(originalBeforeSql.trim(), dbType);
        String originalAfterSql = sql.substring(cursor);
        String afterSql = DefaultSqlSyntaxHandler.buildSqlByKeywordsAfterCursor(originalAfterSql.trim(), dbType);
        String matchSql = beforeSql + " " + afterSql;
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        String datasourceName = connectInfo == null ? null : connectInfo.getAlias();
        Connection connection = Chat2DBContext.getConnection();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        if (metaData == null) {
            return List.of();
        }
        ISQLIdentifierProcessor sqlIdentifierProcessor = metaData.getSQLIdentifierProcessor();
        CompletionConfig config = new CompletionConfig(Boolean.TRUE.equals(param.getNeedFullName()));

        if (matchesPattern(beforeSql, END_PERIOD_PATTERN) && StringUtils.isBlank(originalAfterSql)
                || matchesPattern(originalAfterSql, STARTS_WITH_SPACE_NEWLINE_SEMICOLON_OR_RIGHT_PARENTHESIS)) {
            Matcher matcher = END_PERIOD_PATTERN.matcher(beforeSql);
            if (matcher.find()) {
                String tableName = matcher.group(1);
                List<SqlCompletionCandidate> candidates = buildIdentifierResult(CompletionInfo.builder()
                        .dataSourceId(dataSourceId).connection(connection)
                        .metaData(metaData).dbConfig(dbConfig)
                        .tableName(tableName).datasourceName(datasourceName).build(), param);
                if (CollectionUtils.isNotEmpty(candidates)) {
                    return candidates;
                }
                Map<String, String> tableAliasMap = extractTableNameAndAlias(matchSql);
                if (MapUtils.isEmpty(tableAliasMap)) {
                    return List.of();
                }
                boolean needAllColumns = !matchesPattern(beforeSql, KEYWORD_BEFORE_PERIOD_PATTERN);
                List<SqlCompletionCandidate> columnCandidates = buildColumnCandidates(tableAliasMap, CompletionInfo.builder()
                                .dataSourceId(dataSourceId).metaData(metaData)
                                .dbConfig(dbConfig).datasourceName(datasourceName).databaseName(databaseName)
                                .schemaName(schemaName).tableName(tableName).build(),
                        false, needAllColumns);
                if (CollectionUtils.isEmpty(columnCandidates)) {
                    TableNode tableNode = consoleParserCache.getCacheTree().getTableNodeByAlias(tableName);
                    if (tableNode == null) {
                        return List.of();
                    }
                    databaseName = supportDatabase ? tableNode.getDatabaseName() : null;
                    schemaName = supportSchema ? tableNode.getSchemaName() : null;
                    tableAliasMap.clear();
                    tableAliasMap.put(tableNode.getName(), tableNode.getAlias());
                    return buildColumnCandidates(tableAliasMap, CompletionInfo.builder()
                                    .dataSourceId(dataSourceId).metaData(metaData)
                                    .dbConfig(dbConfig).datasourceName(datasourceName).databaseName(databaseName)
                                    .schemaName(schemaName).tableName(tableNode.getAlias()).build(),
                            false, false);
                }
                return columnCandidates;
            }
        }

        if (matchesPattern(beforeSql, FIRST_WORD_SELECT_PATTERN)
                && matchesPattern(afterSql, FIRST_WORD_FROM_PATTERN)
                && matchesPattern(beforeSql + " " + afterSql, SELECT_TIP_COLUMN_FROM_PATTERN)) {
            Map<String, String> tableAliasMap = extractTableNameAndAlias(matchSql);
            if (MapUtils.isEmpty(tableAliasMap)) {
                return List.of();
            }
            List<SqlCompletionCandidate> candidates = buildColumnCandidates(tableAliasMap, CompletionInfo.builder()
                            .dataSourceId(dataSourceId).metaData(metaData)
                            .dbConfig(dbConfig).datasourceName(datasourceName).databaseName(databaseName)
                            .schemaName(schemaName).tableName(null).build(),
                    true, true);
            return CollectionUtils.isEmpty(candidates) ? List.of() : candidates;
        }

        if (matchesPattern(beforeSql, LAST_WORD_WHERE_OR_AND_PATTERN)) {
            Map<String, String> tableAliasMap = extractTableNameAndAlias(matchSql);
            if (MapUtils.isEmpty(tableAliasMap)) {
                return List.of();
            }
            List<SqlCompletionCandidate> candidates = buildColumnCandidates(tableAliasMap, CompletionInfo.builder()
                            .dataSourceId(dataSourceId).metaData(metaData)
                            .dbConfig(dbConfig).datasourceName(datasourceName).databaseName(databaseName)
                            .schemaName(schemaName).tableName(null).build(),
                    true, false);
            return CollectionUtils.isEmpty(candidates) ? List.of() : candidates;
        }

        if (matchesAnyPattern(beforeSql + " )", INSERT_INTO_COLUMN_PATTERN, REPLACE_INTO_COLUMN_PATTERN,
                INSERT_IGNORE_INTO_COLUMN_PATTERN)
                || matchesAnyPattern(matchSql, INSERT_INTO_COLUMN_PATTERN, REPLACE_INTO_COLUMN_PATTERN,
                INSERT_IGNORE_INTO_COLUMN_PATTERN)) {
            List<String> matcher = extractInsertIntoTableColumns(beforeSql + " )");
            if (CollectionUtils.isEmpty(matcher)) {
                return List.of();
            }
            String tableName = matcher.get(0);
            String columns = matcher.get(1);
            Set<String> columnSet = null;
            if (StringUtils.isNotBlank(columns)) {
                columnSet = Arrays.stream(columns.split("\\s*,\\s*"))
                        .filter(column -> !column.isEmpty())
                        .map(sqlIdentifierProcessor::removeIdentifierQuote)
                        .collect(Collectors.toSet());
            }
            HashMap<String, String> tableAliasMap = new HashMap<>();
            tableAliasMap.put(tableName, "");
            List<SqlCompletionCandidate> candidates = buildColumnCandidates(tableAliasMap, CompletionInfo.builder()
                            .dataSourceId(dataSourceId).metaData(metaData)
                            .dbConfig(dbConfig).datasourceName(datasourceName).databaseName(databaseName)
                            .schemaName(schemaName).tableName(tableName).build(),
                    false, CollectionUtils.isEmpty(columnSet));
            if (CollectionUtils.isEmpty(candidates)) {
                return List.of();
            }
            if (CollectionUtils.isNotEmpty(columnSet)) {
                Set<String> finalColumnSet = columnSet;
                List<SqlCompletionCandidate> filtered = candidates.stream()
                        .filter(candidate -> !finalColumnSet.contains(candidate.getLabel()))
                        .toList();
                return CollectionUtils.isEmpty(filtered) ? List.of() : filtered;
            }
            return candidates;
        }

        if (matchesPattern(beforeSql, UPDATE_SET_COLUMN_PATTERN)) {
            Matcher matcher = UPDATE_SET_COLUMN_PATTERN.matcher(beforeSql);
            matcher.find();
            String tableName = matcher.group(1);
            String alias = matcher.group(3);
            String columns = matcher.group(4);
            if (StringUtils.isNotBlank(columns) && !beforeSql.trim().endsWith(",")) {
                return List.of();
            }
            boolean needAliasPrefix = StringUtils.isNotBlank(alias);
            HashMap<String, String> tableAliasMap = new HashMap<>();
            tableAliasMap.put(tableName, needAliasPrefix ? alias : "");
            List<SqlCompletionCandidate> candidates = buildColumnCandidates(tableAliasMap, CompletionInfo.builder()
                            .dataSourceId(dataSourceId).metaData(metaData)
                            .dbConfig(dbConfig).datasourceName(datasourceName).databaseName(databaseName)
                            .schemaName(schemaName).tableName(tableName).build(),
                    needAliasPrefix, false);
            if (CollectionUtils.isEmpty(candidates)) {
                return List.of();
            }
            if (StringUtils.isBlank(columns)) {
                return candidates;
            }
            Set<String> columnNames = Arrays.stream(columns.split("\\s*,\\s*"))
                    .map(s -> s.split("\\s*=\\s*")[0])
                    .map(s -> s.replaceAll("^[^.]+\\.", ""))
                    .map(sqlIdentifierProcessor::removeIdentifierQuote)
                    .collect(Collectors.toSet());
            List<SqlCompletionCandidate> filtered = candidates.stream()
                    .filter(candidate -> !columnNames.contains(candidate.getLabel()))
                    .toList();
            return CollectionUtils.isEmpty(filtered) ? List.of() : filtered;
        }

        if (matchesAnyPattern(beforeSql, SELECT_STAR_FROM_PATTERN, INSERT_INTO_PATTERN,
                UPDATE_TABLE_PATTERN, DELETE_FROM_PATTERN, SELECT_COUNT_FROM_PATTERN, SELECT_FROM_PATTERN,
                REPLACE_INTO_PATTERN, INSERT_IGNORE_INTO_PATTERN)) {
            if (supportDatabase && StringUtils.isBlank(databaseName)) {
                return List.of();
            }
            if (supportSchema && StringUtils.isBlank(schemaName)) {
                return List.of();
            }
            List<SqlCompletionCandidate> tables = buildTableCandidates(CompletionInfo.builder()
                    .dataSourceId(dataSourceId).connection(connection)
                    .metaData(metaData).datasourceName(datasourceName)
                    .databaseName(databaseName).schemaName(schemaName).build(), config);
            if (matchesPattern(beforeSql, SELECT_STAR_FROM_PATTERN)) {
                List<SqlCompletionCandidate> views = buildViewCandidates(
                        CompletionInfo.builder().metaData(metaData).connection(connection)
                                .datasourceName(datasourceName).dbType(dbType)
                                .databaseName(databaseName).schemaName(schemaName).build(),
                        config);
                tables.addAll(views);
            }
            return tables;
        }

        if (matchesPattern(beforeSql, LAST_WORD_JOIN_PATTERN)) {
            Map<String, String> tableAliasMap = extractTableNamesWithPattern(beforeSql, SELECT_TABLE_NAME_PATTERN);
            if (MapUtils.isEmpty(tableAliasMap)) {
                return List.of();
            }
            Set<String> processedKeys = new HashSet<>();
            ArrayList<SqlCompletionCandidate> candidates = new ArrayList<>();
            StringBuilder joinClauseBuilder = new StringBuilder();
            String tableName = "";
            String tableAlias = "";
            for (Map.Entry<String, String> tableAliasEntry : tableAliasMap.entrySet()) {
                tableName = tableAliasEntry.getKey();
                String[] split = tableName.split("\\.");
                if (split.length == 3) {
                    databaseName = split[0];
                    schemaName = split[1];
                } else if (split.length == 2) {
                    if (supportDatabase) {
                        databaseName = split[0];
                    } else {
                        schemaName = split[0];
                    }
                }
                tableName = split[split.length - 1];
                databaseName = normalizeIdentifierPart(sqlIdentifierProcessor, databaseName);
                schemaName = normalizeIdentifierPart(sqlIdentifierProcessor, schemaName);
                tableName = normalizeIdentifierPart(sqlIdentifierProcessor, tableName);
                tableAlias = sqlIdentifierProcessor.quoteIdentifier(tableAliasEntry.getValue());
                if (supportDatabase && StringUtils.isBlank(databaseName)) {
                    continue;
                }
                if (supportSchema && StringUtils.isBlank(schemaName)) {
                    continue;
                }
                List<ForeignKeyInfo> importedKeys = metaData.getImportedKeys(connection,
                        new TableMetadataRequest(databaseName, schemaName, tableName));
                for (ForeignKeyInfo importedKey : importedKeys) {
                    String pkTableName = sqlIdentifierProcessor.quoteIdentifier(importedKey.getPkTableName());
                    String pkColumnName = sqlIdentifierProcessor.quoteIdentifier(importedKey.getPkColumnName());
                    String fkTableName = sqlIdentifierProcessor.quoteIdentifier(importedKey.getFkTableName());
                    String fkTableAlias = StringUtils.isBlank(tableAlias) ? fkTableName : tableAlias;
                    String fkColumnName = sqlIdentifierProcessor.quoteIdentifier(importedKey.getFkColumnName());
                    String pkTableAlias = generateAlias(pkTableName, tableAliasMap);
                    joinClauseBuilder.append(" ").append(pkTableName).append(" ").append(pkTableAlias)
                            .append(" on ").append(pkTableAlias).append(".").append(pkColumnName)
                            .append(" = ").append(fkTableAlias).append(".").append(fkColumnName).append(" ");
                    addJoinClauseCandidate(joinClauseBuilder, datasourceName, databaseName, schemaName, tableName, candidates);
                    processedKeys.add(pkTableName + "." + pkColumnName);
                }
                List<ForeignKeyInfo> exportedKeys = metaData.getExportedKeys(connection,
                        new TableMetadataRequest(databaseName, schemaName, tableName));
                for (ForeignKeyInfo exportedKey : exportedKeys) {
                    String pkTableName = sqlIdentifierProcessor.quoteIdentifier(exportedKey.getPkTableName());
                    String pkColumnName = sqlIdentifierProcessor.quoteIdentifier(exportedKey.getPkColumnName());
                    String fkTableName = sqlIdentifierProcessor.quoteIdentifier(exportedKey.getFkTableName());
                    String fkTableAlias = generateAlias(fkTableName, tableAliasMap);
                    String fkColumnName = sqlIdentifierProcessor.quoteIdentifier(exportedKey.getFkColumnName());
                    String pkTableAlias = StringUtils.isBlank(tableAlias) ? pkTableName : tableAlias;
                    joinClauseBuilder.append(" ").append(fkTableName).append(" ").append(fkTableAlias)
                            .append(" on ").append(pkTableAlias).append(".").append(pkColumnName)
                            .append(" = ").append(fkTableAlias).append(".").append(fkColumnName).append(" ");
                    addJoinClauseCandidate(joinClauseBuilder, datasourceName, databaseName, schemaName, tableName, candidates);
                    processedKeys.add(fkTableName + "." + fkColumnName);
                }
            }

            List<TableColumn> tableColumns = getTableColumns(dataSourceId, databaseName, schemaName,
                    tableName, metaData, connection, null);
            if (CollectionUtils.isEmpty(tableColumns)) {
                return List.of();
            }
            Set<String> columnNameSet = tableColumns.stream().map(TableColumn::getName).collect(Collectors.toSet());
            String tableKey = getTableKey(dataSourceId, databaseName, schemaName);
            List<Table> tables = MemoryCacheManage.computeIfAbsent(tableKey, () -> {
                List<Table> tableList = metaData.tables(connection,
                        new TablesRequest(param.getDatabaseName(), param.getSchemaName(), null));
                return new ArrayList<>(tableList);
            });
            if (CollectionUtils.isEmpty(tables) || tables.size() < 2) {
                return List.of();
            }
            for (Table table : tables) {
                String targetTableName = table.getName();
                if (StringUtils.equals(targetTableName, tableName)) {
                    continue;
                }
                getColumnKey(dataSourceId, databaseName, schemaName, tableName);
                DbTableQueryRequest queryParam = DbTableQueryRequest.builder().dataSourceId(dataSourceId)
                        .databaseName(databaseName).schemaName(schemaName)
                        .tableName(targetTableName).build();
                List<TableColumn> columns = tableService.queryColumns(queryParam);
                if (CollectionUtils.isEmpty(columns)) {
                    continue;
                }
                for (TableColumn column : columns) {
                    String columnName = sqlIdentifierProcessor.quoteIdentifier(column.getName());
                    if (!columnNameSet.contains(columnName)) {
                        continue;
                    }
                    String processedKey = targetTableName + "." + columnName;
                    if (processedKeys.contains(processedKey)) {
                        continue;
                    }
                    String targetTableAlias = generateAlias(targetTableName, tableAliasMap);
                    tableAlias = StringUtils.isNotBlank(tableAlias) ? tableAlias : tableName;
                    joinClauseBuilder.append(" ").append(targetTableName).append(" ").append(targetTableAlias)
                            .append(" on ").append(tableAlias).append(".").append(columnName)
                            .append(" = ").append(targetTableAlias).append(".").append(columnName).append(" ");
                    addJoinClauseCandidate(joinClauseBuilder, datasourceName, databaseName, schemaName, tableName, candidates);
                    processedKeys.add(processedKey);
                }
            }
            return candidates;
        }
        return List.of();
    }

    private static void addJoinClauseCandidate(StringBuilder joinClauseBuilder,
                                               String datasourceName,
                                               String databaseName,
                                               String schemaName,
                                               String tableName,
                                               ArrayList<SqlCompletionCandidate> candidates) {
        SqlCompletionCandidate candidate = candidate(SqlCompletionCandidateTypeEnum.JOIN_CLAUSE,
                joinClauseBuilder.toString());
        candidate.setDatasourceName(datasourceName);
        candidate.setDatabaseName(databaseName);
        candidate.setSchemaName(schemaName);
        candidate.setTableName(tableName);
        candidates.add(candidate);
        joinClauseBuilder.setLength(0);
    }

    private List<SqlCompletionCandidate> buildViewCandidates(CompletionInfo info, CompletionConfig config) {
        List<SqlCompletionCandidate> views = new ArrayList<>();
        Connection connection = info.getConnection();
        String databaseName = info.getDatabaseName();
        String schemaName = info.getSchemaName();
        IDbMetaData metaData = info.getMetaData();
        String datasourceName = info.getDatasourceName();
        ISQLIdentifierProcessor processor = metaData.getSQLIdentifierProcessor();
        for (Table view : metaData.views(connection, databaseName, schemaName)) {
            String viewName = view.getName();
            SqlCompletionCandidate candidate = candidate(SqlCompletionCandidateTypeEnum.VIEW, viewName);
            candidate.setDatabaseName(databaseName);
            candidate.setSchemaName(schemaName);
            candidate.setTableName(viewName);
            candidate.setObjectName(viewName);
            candidate.setDatasourceName(datasourceName);
            candidate.setObjectType(view.getType());
            candidate.setComment(view.getComment());
            String quotedViewName = processor.quoteIdentifier(viewName);
            candidate.setInsertText(config.isNeedFullName()
                    ? buildFullName(processor.quoteIdentifier(databaseName), processor.quoteIdentifier(schemaName), quotedViewName)
                    : quotedViewName);
            views.add(candidate);
        }
        return views;
    }

    private List<SqlCompletionCandidate> buildTableCandidates(CompletionInfo info, CompletionConfig config) {
        List<SqlCompletionCandidate> tableCandidates = new ArrayList<>();
        Connection connection = info.getConnection();
        String databaseName = info.getDatabaseName();
        String schemaName = info.getSchemaName();
        Long dataSourceId = info.getDataSourceId();
        IDbMetaData metaData = info.getMetaData();
        String datasourceName = info.getDatasourceName();
        ISQLIdentifierProcessor processor = metaData.getSQLIdentifierProcessor();
        String tableKey = getTableKey(dataSourceId, databaseName, schemaName);
        List<Table> tableList = MemoryCacheManage.computeIfAbsent(tableKey, () -> {
            List<Table> tables = metaData.tables(connection, new TablesRequest(databaseName, schemaName, null));
            return new ArrayList<>(tables);
        });
        for (Table table : tableList) {
            String tableName = table.getName();
            SqlCompletionCandidate candidate = candidate(SqlCompletionCandidateTypeEnum.TABLE, tableName);
            candidate.setDatasourceName(datasourceName);
            candidate.setDatabaseName(databaseName);
            candidate.setSchemaName(schemaName);
            candidate.setTableName(tableName);
            candidate.setObjectType(table.getType());
            candidate.setComment(table.getComment());
            String quotedTableName = processor.quoteIdentifier(tableName);
            candidate.setInsertText(config.isNeedFullName()
                    ? buildFullName(processor.quoteIdentifier(databaseName), processor.quoteIdentifier(schemaName), quotedTableName)
                    : quotedTableName);
            tableCandidates.add(candidate);
        }
        return tableCandidates;
    }

    private static String buildFullName(String databaseName, String schemaName, String tableName) {
        StringBuilder fullNameBuilder = new StringBuilder(20);
        if (StringUtils.isNotBlank(databaseName)) {
            fullNameBuilder.append(databaseName).append(".");
        }
        if (StringUtils.isNotBlank(schemaName)) {
            fullNameBuilder.append(schemaName).append(".");
        }
        fullNameBuilder.append(tableName);
        return fullNameBuilder.toString();
    }

    private List<SqlCompletionCandidate> buildColumnCandidates(Map<String, String> tableAliasMap,
                                                               CompletionInfo info,
                                                               boolean needAliasPrefix,
                                                               boolean needAllColumns) {
        DBConfig dbConfig = info.getDbConfig();
        String tableName = info.getTableName();
        String databaseName = info.getDatabaseName();
        String schemaName = info.getSchemaName();
        IDbMetaData metaData = info.getMetaData();
        String datasourceName = info.getDatasourceName();
        Long dataSourceId = info.getDataSourceId();
        ISQLIdentifierProcessor processor = metaData.getSQLIdentifierProcessor();
        boolean supportSchema = dbConfig.isSupportSchema();
        boolean supportDatabase = dbConfig.isSupportDatabase();
        if (StringUtils.isNotBlank(tableName)) {
            if (!tableAliasMap.containsKey(tableName) && !tableAliasMap.containsValue(tableName)) {
                return null;
            }
            Map.Entry<String, String> matchedEntry = null;
            for (Map.Entry<String, String> entry : tableAliasMap.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if ((StringUtils.isNotBlank(key) && key.equals(tableName))
                        || (StringUtils.isNotBlank(value) && value.equals(tableName))) {
                    matchedEntry = entry;
                    break;
                }
            }
            if (matchedEntry != null) {
                tableAliasMap.clear();
                tableAliasMap.put(matchedEntry.getKey(), matchedEntry.getValue());
            }
        }

        List<SqlCompletionCandidate> candidates = new ArrayList<>(100);
        for (Map.Entry<String, String> entry : tableAliasMap.entrySet()) {
            String table = entry.getKey();
            String[] splitName = table.split("\\.");
            int length = splitName.length;
            if (length == 3) {
                databaseName = splitName[0];
                schemaName = splitName[1];
            } else if (length == 2) {
                if (supportSchema) {
                    schemaName = splitName[0];
                } else {
                    databaseName = splitName[0];
                }
            }
            databaseName = normalizeIdentifierPart(processor, databaseName);
            if (supportDatabase && StringUtils.isBlank(databaseName)) {
                continue;
            }
            schemaName = normalizeIdentifierPart(processor, schemaName);
            if (supportSchema && StringUtils.isBlank(schemaName)) {
                continue;
            }
            String lastIdentifier = splitName[length - 1];
            boolean quoted = processor.isQuoteIdentifier(lastIdentifier);
            table = normalizeIdentifierPart(processor, lastIdentifier);
            if (!quoted) {
                table = processor.convertIdentifierCase(table);
            }
            String alias = entry.getValue();
            List<TableColumn> columns = getTableColumns(dataSourceId, databaseName, schemaName, table,
                    metaData, Chat2DBContext.getConnection(), null);
            if (CollectionUtils.isEmpty(columns)) {
                continue;
            }
            List<SqlCompletionCandidate> tableCandidates = new ArrayList<>(columns.size() + 1);
            if (needAllColumns) {
                tableCandidates.add(null);
            }
            StringBuilder columnBuilder = new StringBuilder();
            for (TableColumn column : columns) {
                if (StringUtils.isNotBlank(alias) && needAliasPrefix) {
                    columnBuilder.append(alias).append(".");
                }
                String columnName = column.getName();
                SqlCompletionCandidate candidate = candidate(SqlCompletionCandidateTypeEnum.COLUMN, columnName);
                String quotedColumnName = processor.quoteIdentifier(columnName);
                columnBuilder.append(quotedColumnName);
                candidate.setInsertText(columnBuilder.toString());
                candidate.setDatasourceName(datasourceName);
                candidate.setDatabaseName(databaseName);
                candidate.setSchemaName(schemaName);
                candidate.setTableName(table);
                candidate.setColumnName(quotedColumnName);
                candidate.setDataType(column.getColumnType());
                candidate.setComment(column.getComment());
                tableCandidates.add(candidate);
                columnBuilder.setLength(0);
            }
            if (needAllColumns) {
                if (tableCandidates.size() >= 3) {
                    SqlCompletionCandidate candidate = candidate(SqlCompletionCandidateTypeEnum.COLUMN, null);
                    String allColumn = Stream.concat(
                            tableCandidates.stream().skip(1).limit(1).map(SqlCompletionCandidate::getLabel),
                            tableCandidates.stream().skip(2).map(columnCandidate -> {
                                if (StringUtils.isNotBlank(alias) && !needAliasPrefix) {
                                    return alias + "." + columnCandidate.getLabel();
                                }
                                return columnCandidate.getLabel();
                            })
                    ).collect(Collectors.joining(", "));
                    candidate.setLabel(allColumn);
                    candidate.setInsertText(allColumn);
                    candidate.setDatasourceName(datasourceName);
                    candidate.setDatabaseName(databaseName);
                    candidate.setSchemaName(schemaName);
                    candidate.setTableName(table);
                    candidate.setColumnName(allColumn);
                    tableCandidates.set(0, candidate);
                } else {
                    tableCandidates.remove(0);
                }
            }
            candidates.addAll(tableCandidates);
        }
        return CollectionUtils.isEmpty(candidates) ? null : candidates;
    }

    private List<SqlCompletionCandidate> buildIdentifierResult(CompletionInfo info, DbSqlCompletionGetRequest param) {
        CompletionConfig config = new CompletionConfig(false);
        DBConfig dbConfig = info.getDbConfig();
        String name = info.getTableName();
        IDbMetaData metaData = info.getMetaData();
        Connection connection = info.getConnection();
        ISQLIdentifierProcessor processor = metaData.getSQLIdentifierProcessor();
        String datasourceName = info.getDatasourceName();
        Long dataSourceId = info.getDataSourceId();
        boolean supportSchema = dbConfig.isSupportSchema();
        boolean supportDatabase = dbConfig.isSupportDatabase();
        String dbType = dbConfig.getDbType().toUpperCase();
        if (StringUtils.isBlank(name)) {
            return null;
        }
        String[] names = name.split("\\.");
        if (names.length == 0) {
            return null;
        }
        String paramDatabaseName = param.getDatabaseName();
        String paramSchemaName = param.getSchemaName();
        String lastName = normalizeIdentifierPart(processor, names[names.length - 1]);
        List<Database> databases = metaData.databases(connection);
        if (CollectionUtils.isNotEmpty(databases)) {
            for (Database database : databases) {
                String databaseName = database.getName();
                if (!StringUtils.equalsIgnoreCase(databaseName, lastName)) {
                    continue;
                }
                List<Schema> schemas = metaData.schemas(connection, databaseName);
                if (CollectionUtils.isNotEmpty(schemas)) {
                    return schemas.stream().map(schema -> {
                        String schemaName = schema.getName();
                        SqlCompletionCandidate candidate = candidate(SqlCompletionCandidateTypeEnum.SCHEMA, schemaName);
                        candidate.setInsertText(processor.quoteIdentifier(schemaName));
                        candidate.setDatasourceName(datasourceName);
                        candidate.setDatabaseName(databaseName);
                        candidate.setSchemaName(schemaName);
                        candidate.setComment(schema.getComment());
                        return candidate;
                    }).toList();
                }
                List<SqlCompletionCandidate> tablesAndViews = buildTableCandidates(CompletionInfo.builder()
                        .dataSourceId(dataSourceId).connection(connection)
                        .metaData(metaData).datasourceName(datasourceName)
                        .databaseName(databaseName).schemaName(null).build(), config);
                tablesAndViews.addAll(buildViewCandidates(CompletionInfo.builder().metaData(metaData).connection(connection)
                        .datasourceName(datasourceName).dbType(dbType)
                        .databaseName(databaseName).schemaName(null).build(), config));
                return tablesAndViews;
            }
        }

        String databaseName = names.length - 2 < 0 ? paramDatabaseName : names[names.length - 2];
        databaseName = normalizeIdentifierPart(processor, databaseName);
        List<Schema> schemas = metaData.schemas(connection, databaseName);
        if (CollectionUtils.isNotEmpty(schemas)) {
            for (Schema schema : schemas) {
                String schemaName = schema.getName();
                if (!StringUtils.equalsIgnoreCase(schemaName, lastName)) {
                    continue;
                }
                List<SqlCompletionCandidate> tableAndViewCandidates = buildTableCandidates(CompletionInfo.builder()
                        .dataSourceId(dataSourceId).connection(connection)
                        .metaData(metaData).datasourceName(datasourceName)
                        .databaseName(databaseName).schemaName(schemaName).build(), config);
                tableAndViewCandidates.addAll(buildViewCandidates(CompletionInfo.builder()
                        .metaData(metaData).connection(connection)
                        .datasourceName(datasourceName).dbType(dbType)
                        .databaseName(databaseName).schemaName(schemaName)
                        .build(), config));
                return tableAndViewCandidates;
            }
        }
        if (supportSchema) {
            databaseName = names.length - 3 < 0 ? paramDatabaseName : names[names.length - 3];
        } else {
            databaseName = names.length - 2 < 0 ? paramDatabaseName : names[names.length - 2];
        }
        if (supportDatabase && StringUtils.isBlank(databaseName)) {
            return List.of();
        }
        String schemaName = null;
        if (supportSchema) {
            schemaName = names.length - 2 < 0 ? paramSchemaName : names[names.length - 2];
        }
        if (supportSchema && StringUtils.isBlank(schemaName)) {
            return List.of();
        }
        databaseName = normalizeIdentifierPart(processor, databaseName);
        schemaName = normalizeIdentifierPart(processor, schemaName);
        String tableKey = getTableKey(dataSourceId, databaseName, schemaName);
        List<Table> tables = MemoryCacheManage.get(tableKey);
        if (CollectionUtils.isEmpty(tables)) {
            tables = metaData.tables(connection, new TablesRequest(databaseName, schemaName, null));
        }
        if (CollectionUtils.isEmpty(tables)) {
            return List.of();
        }
        for (Table table : tables) {
            if (!StringUtils.equalsIgnoreCase(table.getName(), lastName)) {
                continue;
            }
            String tableName = table.getName();
            List<SqlCompletionCandidate> columnCandidates = new ArrayList<>();
            for (TableColumn column : getTableColumns(dataSourceId, databaseName, schemaName,
                    tableName, metaData, connection, null)) {
                String columnName = column.getName();
                SqlCompletionCandidate candidate = candidate(SqlCompletionCandidateTypeEnum.COLUMN, columnName);
                candidate.setInsertText(processor.quoteIdentifier(columnName));
                candidate.setDataType(column.getColumnType());
                candidate.setDatasourceName(datasourceName);
                candidate.setDatabaseName(databaseName);
                candidate.setSchemaName(schemaName);
                candidate.setTableName(tableName);
                candidate.setColumnName(columnName);
                candidate.setComment(column.getComment());
                columnCandidates.add(candidate);
            }
            return columnCandidates;
        }
        return null;
    }

    private List<TableColumn> getTableColumns(Long datasourceId,
                                              String database,
                                              String schema,
                                              String tableName,
                                              IDbMetaData metaData,
                                              Connection connection,
                                              String columnName) {
        DbTableQueryRequest queryParam = DbTableQueryRequest.builder().dataSourceId(datasourceId)
                .databaseName(database).schemaName(schema)
                .tableName(tableName).build();
        List<TableColumn> columns = tableService.queryColumns(queryParam);
        if (CollectionUtils.isEmpty(columns)) {
            columns = metaData.columns(connection, new ColumnMetadataRequest(database, schema, tableName, columnName));
        }
        return columns;
    }

    private static String generateAlias(String tableName, Map<String, String> tableAliasMap) {
        String[] words = tableName.split("_");
        StringBuilder aliasBuilder = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                aliasBuilder.append(Character.toLowerCase(word.charAt(0)));
            }
        }
        String alias = aliasBuilder.toString();
        int counter = 1;
        int retryCount = 0;
        while (tableAliasMap.containsValue(alias) && retryCount < 10) {
            alias = aliasBuilder + String.valueOf(counter);
            counter++;
            retryCount++;
        }
        if (alias.length() == 1) {
            alias += "1";
        }
        return alias;
    }

    private static SqlCompletionCandidate candidate(SqlCompletionCandidateTypeEnum type, String label) {
        return SqlCompletionCandidate.of(type, label);
    }

    static String normalizeIdentifierPart(ISQLIdentifierProcessor processor, String identifier) {
        return identifier == null ? null : processor.removeIdentifierQuote(identifier);
    }

    private int resolveCursor(DbSqlCompletionGetRequest param) {
        if (param.getCursor() != null) {
            return Math.max(0, Math.min(param.getCursor(), Objects.toString(param.getSql(), "").length()));
        }
        return param.getSql() == null ? 0 : param.getSql().length();
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    private static class CompletionConfig {
        private boolean needFullName;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    private static class CompletionInfo {
        private Long dataSourceId;
        private DBConfig dbConfig;
        private IDbMetaData metaData;
        private Connection connection;
        private String datasourceName;
        private String dbType;
        private String databaseName;
        private String schemaName;
        private String tableName;
    }
}
