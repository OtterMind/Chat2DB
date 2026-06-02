package ai.chat2db.server.domain.core.impl;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ai.chat2db.spi.SqlBuilder;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ai.chat2db.server.domain.api.model.DataSource;
import ai.chat2db.server.domain.api.model.schemaDiff.ColumnDiff;
import ai.chat2db.server.domain.api.model.schemaDiff.DiffSummary;
import ai.chat2db.server.domain.api.model.schemaDiff.FieldDiff;
import ai.chat2db.server.domain.api.model.schemaDiff.ForeignKeyDiff;
import ai.chat2db.server.domain.api.model.schemaDiff.IndexDiff;
import ai.chat2db.server.domain.api.model.schemaDiff.SchemaDiffResult;
import ai.chat2db.server.domain.api.model.schemaDiff.TableDiff;
import ai.chat2db.server.domain.api.model.schemaDiff.TableDiffType;
import ai.chat2db.server.domain.api.param.DeprecatedTableParam;
import ai.chat2db.server.domain.api.param.schemaDiff.CompareOption;
import ai.chat2db.server.domain.api.param.schemaDiff.MigrateResult;
import ai.chat2db.server.domain.api.param.schemaDiff.MigrationStatementResult;
import ai.chat2db.server.domain.api.param.schemaDiff.SchemaCompareParam;
import ai.chat2db.server.domain.api.param.schemaDiff.SchemaMigrateParam;
import ai.chat2db.server.domain.api.service.DataSourceAccessBusinessService;
import ai.chat2db.server.domain.api.service.DataSourceService;
import ai.chat2db.server.domain.api.service.DeprecatedTableService;
import ai.chat2db.server.domain.api.service.SchemaDiffService;
import ai.chat2db.server.tools.common.util.ContextUtils;
import ai.chat2db.spi.MetaData;
import ai.chat2db.spi.Plugin;
import ai.chat2db.spi.enums.EditStatus;
import ai.chat2db.spi.model.ForeignKey;
import ai.chat2db.spi.model.Table;
import ai.chat2db.spi.model.TableColumn;
import ai.chat2db.spi.model.TableIndex;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * Schema diff and migration service implementation.
 * Provides functionality to compare database structures between source and target,
 * and execute DDL migration statements.
 */
@Service
@Slf4j
public class SchemaDiffServiceImpl implements SchemaDiffService {

    private static final Pattern MYSQL_INTEGER_COLUMN_TYPE_WITH_WIDTH = Pattern.compile(
            "(?i)^(tinyint|smallint|mediumint|int|integer|bigint)\\s*\\(\\s*\\d+\\s*\\)(\\s+unsigned)?$");

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private DataSourceAccessBusinessService dataSourceAccessBusinessService;

    @Autowired
    private DeprecatedTableService deprecatedTableService;

    /**
     * Compare database structures between source and target.
     * Supports case-sensitive and case-insensitive comparison modes.
     * 
     * @param param comparison parameters including source/target connection info and options
     * @return schema diff result containing table differences and DDL statements
     */
    /**
     * 数据库比较上下文，封装比较过程中的所有必要信息
     */
    private static class CompareContext {
        final Plugin sourcePlugin;
        final Plugin targetPlugin;
        final MetaData sourceMeta;
        final MetaData targetMeta;
        final Connection sourceConn;
        final Connection targetConn;
        final CompareOption option;
        final Set<String> sourceDeprecated;
        final Set<String> targetDeprecated;
        final List<String> sourceTableNames;
        final List<String> targetTableNames;
        final Map<String, Table> sourceTableDetails;
        final Map<String, Table> targetTableDetails;

        CompareContext(Plugin sourcePlugin, Plugin targetPlugin, MetaData sourceMeta, MetaData targetMeta,
                      Connection sourceConn, Connection targetConn, CompareOption option,
                      Set<String> sourceDeprecated, Set<String> targetDeprecated,
                      List<String> sourceTableNames, List<String> targetTableNames,
                      Map<String, Table> sourceTableDetails, Map<String, Table> targetTableDetails) {
            this.sourcePlugin = sourcePlugin;
            this.targetPlugin = targetPlugin;
            this.sourceMeta = sourceMeta;
            this.targetMeta = targetMeta;
            this.sourceConn = sourceConn;
            this.targetConn = targetConn;
            this.option = option;
            this.sourceDeprecated = sourceDeprecated;
            this.targetDeprecated = targetDeprecated;
            this.sourceTableNames = sourceTableNames;
            this.targetTableNames = targetTableNames;
            this.sourceTableDetails = sourceTableDetails;
            this.targetTableDetails = targetTableDetails;
        }
    }

    /**
     * 表比较结果统计
     */
    private static class CompareStats {
        int onlyInSource = 0;
        int onlyInTarget = 0;
        int modified = 0;
        int unchanged = 0;
    }

    @Override
    public SchemaDiffResult compare(SchemaCompareParam param) {
        long startTime = System.currentTimeMillis();
        log.info("Schema compare started, source: {}, target: {}", 
                param.getSourceDataSourceId(), param.getTargetDataSourceId());
        
        // 步骤1：获取数据库插件
        final Plugin sourcePlugin = getPlugin(param.getSourceDataSourceId());
        final Plugin targetPlugin = getPlugin(param.getTargetDataSourceId());
        final MetaData sourceMeta = sourcePlugin.getMetaData();
        final MetaData targetMeta = targetPlugin.getMetaData();

        // 步骤2：创建数据库连接（使用 try-with-resources 自动关闭）
        long connStartTime = System.currentTimeMillis();
        try (Connection sourceConn = createConnection(param.getSourceDataSourceId(), param.getSourceDatabaseName(),
                        param.getSourceSchemaName());
             Connection targetConn = createConnection(param.getTargetDataSourceId(), param.getTargetDatabaseName(),
                        param.getTargetSchemaName())) {
            
            log.info("Connections created in {} ms", System.currentTimeMillis() - connStartTime);

            // 步骤3：初始化比较选项和废弃表
            final CompareOption option = validateAndGetOption(param.getCompareOption());
            final Set<String> sourceDeprecated = queryDeprecatedIfNeeded(param, option, true);
            final Set<String> targetDeprecated = queryDeprecatedIfNeeded(param, option, false);

            // 步骤4：并行获取表列表
            List<String> sourceTableNames = fetchTableListsParallel(
                    sourceConn, sourceMeta, param, sourceDeprecated, true);
            List<String> targetTableNames = fetchTableListsParallel(
                    targetConn, targetMeta, param, targetDeprecated, false);

            // 步骤5：批量获取表详情
            Map<String, Table> sourceTableDetails = batchFetchTableDetails(
                    sourceConn, sourceMeta, param.getSourceDatabaseName(),
                    param.getSourceSchemaName(), sourceTableNames);
            Map<String, Table> targetTableDetails = batchFetchTableDetails(
                    targetConn, targetMeta, param.getTargetDatabaseName(),
                    param.getTargetSchemaName(), targetTableNames);

            // 步骤6：构建比较上下文
            CompareContext context = new CompareContext(
                    sourcePlugin, targetPlugin, sourceMeta, targetMeta,
                    sourceConn, targetConn, option,
                    sourceDeprecated, targetDeprecated,
                    sourceTableNames, targetTableNames,
                    sourceTableDetails, targetTableDetails);

            // 步骤7：并行比较所有表
            List<TableDiff> tableDiffs = compareTablesParallel(context);
            CompareStats stats = calculateStats(tableDiffs);

            // 步骤8：构建结果
            return buildCompareResult(param, context, tableDiffs, stats, startTime);

        } catch (Exception e) {
            log.error("Schema compare failed", e);
            throw new RuntimeException("Schema compare failed: " + e.getMessage(), e);
        }
    }

    /**
     * 获取数据库插件并验证
     */
    private Plugin getPlugin(Long dataSourceId) {
        String dbType = getDbType(dataSourceId);
        Plugin plugin = Chat2DBContext.PLUGIN_MAP.get(dbType);
        if (plugin == null) {
            throw new RuntimeException("Plugin not found for database type: " + dbType);
        }
        return plugin;
    }

    /**
     * 验证并获取比较选项
     */
    private CompareOption validateAndGetOption(CompareOption option) {
        if (option == null) {
            throw new RuntimeException("Compare option is required");
        }
        return option;
    }

    /**
     * 根据选项查询废弃表
     */
    private Set<String> queryDeprecatedIfNeeded(SchemaCompareParam param, CompareOption option, boolean isSource) {
        if (!option.isExcludeDeprecated()) {
            return Collections.emptySet();
        }
        
        Long dataSourceId = isSource ? param.getSourceDataSourceId() : param.getTargetDataSourceId();
        String databaseName = isSource ? param.getSourceDatabaseName() : param.getTargetDatabaseName();
        String schemaName = isSource ? param.getSourceSchemaName() : param.getTargetSchemaName();
        
        return queryDeprecatedTableNames(dataSourceId, databaseName, schemaName);
    }

    /**
     * 并行获取表列表
     */
    private List<String> fetchTableListsParallel(Connection conn, MetaData meta, SchemaCompareParam param,
                                                  Set<String> deprecatedSet, boolean isSource) {
        long tableListTime = System.currentTimeMillis();
        
        CompletableFuture<List<String>> tablesFuture = CompletableFuture.supplyAsync(() ->
                listTableNames(conn, meta,
                        isSource ? param.getSourceDatabaseName() : param.getTargetDatabaseName(),
                        isSource ? param.getSourceSchemaName() : param.getTargetSchemaName(),
                        param.getTableNames(), deprecatedSet));
        
        List<String> tableNames = tablesFuture.join();
        log.info("Table lists fetched in {} ms ({}: {} tables)",
                System.currentTimeMillis() - tableListTime,
                isSource ? "source" : "target", tableNames.size());
        
        return tableNames;
    }

    /**
     * 并行比较所有表
     */
    private List<TableDiff> compareTablesParallel(CompareContext context) {
        long compareTime = System.currentTimeMillis();
        
        // 构建表名映射
        Function<String, String> normalizer = context.option.isCaseSensitive()
                ? Function.identity()
                : String::toLowerCase;
        
        Map<String, String> sourceTableMap = context.sourceTableNames.stream()
                .collect(Collectors.toMap(normalizer, n -> n, (a, b) -> a));
        Map<String, String> targetTableMap = context.targetTableNames.stream()
                .collect(Collectors.toMap(normalizer, n -> n, (a, b) -> a));

        Set<String> allNormalizedNames = new HashSet<>();
        allNormalizedNames.addAll(sourceTableMap.keySet());
        allNormalizedNames.addAll(targetTableMap.keySet());

        log.info("Comparing {} tables in parallel", allNormalizedNames.size());

        // 创建并行比较任务
        List<CompletableFuture<TableDiff>> diffFutures = allNormalizedNames.stream()
                .map(normalizedName -> createCompareTask(normalizedName, sourceTableMap, targetTableMap, context))
                .collect(Collectors.toList());

        // 收集结果
        List<TableDiff> tableDiffs = diffFutures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        log.info("Tables compared in {} ms", System.currentTimeMillis() - compareTime);
        return tableDiffs;
    }

    /**
     * 创建单个表的比较任务
     */
    private CompletableFuture<TableDiff> createCompareTask(String normalizedName,
                                                            Map<String, String> sourceTableMap,
                                                            Map<String, String> targetTableMap,
                                                            CompareContext context) {
        String sourceTableName = sourceTableMap.get(normalizedName);
        String targetTableName = targetTableMap.get(normalizedName);
        boolean inSource = sourceTableName != null;
        boolean inTarget = targetTableName != null;

        final String sourceName = sourceTableName;
        final String targetName = targetTableName;
        final boolean inSourceFinal = inSource;
        final boolean inTargetFinal = inTarget;

        return CompletableFuture.supplyAsync(() -> {
            // 仅在源库 -> ADDED
            if (inSourceFinal && !inTargetFinal) {
                return compareTableOnlyInSource(sourceName, context);
            }
            // 仅在目标库 -> REMOVED
            else if (!inSourceFinal && inTargetFinal) {
                return compareTableOnlyInTarget(targetName, context);
            }
            // 两边都存在 -> 比较详情
            else {
                return compareTableInBoth(sourceName, targetName, context);
            }
        });
    }

    /**
     * 比较仅在源库中存在的表
     */
    private TableDiff compareTableOnlyInSource(String sourceName, CompareContext context) {
        Table sourceTable = context.sourceTableDetails.get(sourceName);
        if (sourceTable == null) {
            log.warn("Source table {} not found, skipping", sourceName);
            return null;
        }
        
        String ddl = context.sourcePlugin.getMetaData().getSqlBuilder().buildCreateTableSql(sourceTable);
        return TableDiff.builder()
                .tableName(sourceName)
                .diffType(TableDiffType.ADDED)
                .sourceTable(sourceTable)
                .ddlStatement(ddl)
                .ddlStatements(Collections.singletonList(ddl))
                .build();
    }

    /**
     * 比较仅在目标库中存在的表
     */
    private TableDiff compareTableOnlyInTarget(String targetName, CompareContext context) {
        Table targetTable = context.targetTableDetails.get(targetName);
        if (targetTable == null) {
            log.warn("Target table {} not found, skipping", targetName);
            return null;
        }
        
        return TableDiff.builder()
                .tableName(targetName)
                .diffType(TableDiffType.REMOVED)
                .targetTable(targetTable)
                .ddlStatements(Collections.emptyList())
                .build();
    }

    /**
     * 比较两边都存在的表
     */
    private TableDiff compareTableInBoth(String sourceName, String targetName, CompareContext context) {
        Table sourceTable = context.sourceTableDetails.get(sourceName);
        Table targetTable = context.targetTableDetails.get(targetName);
        
        if (sourceTable == null || targetTable == null) {
            log.warn("Table details missing: source={}, target={}", sourceName, targetName);
            return null;
        }

        // 比较列、索引、外键
        List<ColumnDiff> columnDiffs = context.option.isCompareColumn()
                ? compareColumns(sourceTable.getColumnList(), targetTable.getColumnList(), context.option)
                : Collections.emptyList();
        List<IndexDiff> indexDiffs = context.option.isCompareIndex()
                ? compareIndexes(sourceTable.getIndexList(), targetTable.getIndexList(), context.option.isCaseSensitive())
                : Collections.emptyList();
        List<ForeignKeyDiff> fkDiffs = context.option.isCompareForeignKey()
                ? compareForeignKeys(sourceTable.getForeignKeyList(), targetTable.getForeignKeyList(), context.option.isCaseSensitive())
                : Collections.emptyList();

        boolean hasChanges = !columnDiffs.isEmpty() || !indexDiffs.isEmpty() || !fkDiffs.isEmpty();

        if (context.option.isCompareTableOption()) {
            hasChanges = hasChanges || !tableOptionsEqual(sourceTable, targetTable, context.option);
        }

        if (hasChanges) {
            logSchemaDiffDetails(sourceName, sourceTable, targetTable, columnDiffs, indexDiffs, fkDiffs, context);
            return buildModifiedTableDiff(sourceName, sourceTable, targetTable,
                    columnDiffs, indexDiffs, fkDiffs, context);
        } else {
            return TableDiff.builder()
                    .tableName(sourceName)
                    .diffType(TableDiffType.UNCHANGED)
                    .sourceTable(sourceTable)
                    .targetTable(targetTable)
                    .build();
        }
    }

    private void logSchemaDiffDetails(String tableName, Table sourceTable, Table targetTable,
                                      List<ColumnDiff> columnDiffs, List<IndexDiff> indexDiffs,
                                      List<ForeignKeyDiff> fkDiffs, CompareContext context) {
        if (context.option.isCompareColumn()) {
            for (ColumnDiff diff : columnDiffs) {
                String columnName = getColumnDiffName(diff);
                log.info("Schema compare column diff, table: {}, column: {}, changeType: {}, changedFields: {}",
                        tableName, columnName, diff.getChangeType(), formatChangedFields(diff.getChangedFields()));
            }
        }
        if (context.option.isCompareIndex()) {
            for (IndexDiff diff : indexDiffs) {
                String indexName = getIndexDiffName(diff);
                log.info("Schema compare index diff, table: {}, index: {}, changeType: {}, changedFields: {}",
                        tableName, indexName, diff.getChangeType(), formatChangedFields(diff.getChangedFields()));
            }
        }
        if (context.option.isCompareForeignKey()) {
            for (ForeignKeyDiff diff : fkDiffs) {
                String fkName = getForeignKeyDiffName(diff);
                log.info("Schema compare foreign key diff, table: {}, foreignKey: {}, changeType: {}, changedFields: {}",
                        tableName, fkName, diff.getChangeType(), formatChangedFields(diff.getChangedFields()));
            }
        }
        if (context.option.isCompareTableOption() && !tableOptionsEqual(sourceTable, targetTable, context.option)) {
            log.info("Schema compare table option diff, table: {}, changedFields: {}",
                    tableName, formatChangedFields(describeTableOptionDiff(sourceTable, targetTable, context.option)));
        }
    }

    /**
     * 构建有变更的表差异
     */
    private TableDiff buildModifiedTableDiff(String sourceName, Table sourceTable, Table targetTable,
                                              List<ColumnDiff> columnDiffs, List<IndexDiff> indexDiffs,
                                              List<ForeignKeyDiff> fkDiffs, CompareContext context) {
        Table tableForDDL = buildTableWithEditStatus(sourceTable, targetTable,
                columnDiffs, indexDiffs, fkDiffs, context.option);
        SqlBuilder sqlBuilder = context.targetPlugin.getMetaData().getSqlBuilder();
        String alterDdl = sqlBuilder.buildModifyTaleSql(targetTable, tableForDDL);
        List<FieldDiff> tableOptionDiffs = context.option.isCompareTableOption()
                ? describeTableOptionDiff(sourceTable, targetTable, context.option)
                : Collections.emptyList();

        return TableDiff.builder()
                .tableName(sourceName)
                .diffType(TableDiffType.MODIFIED)
                .sourceTable(sourceTable)
                .targetTable(targetTable)
                .columnDiffs(columnDiffs)
                .indexDiffs(indexDiffs)
                .foreignKeyDiffs(fkDiffs)
                .tableOptionDiffs(tableOptionDiffs)
                .ddlStatement(alterDdl)
                .ddlStatements(StringUtils.isNotBlank(alterDdl)
                        ? Collections.singletonList(alterDdl)
                        : Collections.emptyList())
                .build();
    }

    /**
     * 计算比较统计
     */
    private CompareStats calculateStats(List<TableDiff> tableDiffs) {
        CompareStats stats = new CompareStats();
        for (TableDiff diff : tableDiffs) {
            switch (diff.getDiffType()) {
                case ADDED:
                    stats.onlyInSource++;
                    break;
                case REMOVED:
                    stats.onlyInTarget++;
                    break;
                case MODIFIED:
                    stats.modified++;
                    break;
                case UNCHANGED:
                    stats.unchanged++;
                    break;
            }
        }
        return stats;
    }

    /**
     * 构建比较结果
     */
    private SchemaDiffResult buildCompareResult(SchemaCompareParam param, CompareContext context,
                                                 List<TableDiff> tableDiffs, CompareStats stats, long startTime) {
        // 计算排除的废弃表数量
        int excluded = 0;
        if (context.option.isExcludeDeprecated()) {
            excluded = (int) (context.sourceDeprecated.size() + context.targetDeprecated.size()
                    - new HashSet<>(context.sourceDeprecated).stream()
                            .filter(context.targetDeprecated::contains).count());
        }

        // 计算总表数
        Function<String, String> normalizer = context.option.isCaseSensitive()
                ? Function.identity()
                : String::toLowerCase;
        Set<String> allNormalizedNames = new HashSet<>();
        allNormalizedNames.addAll(context.sourceTableNames.stream().map(normalizer).collect(Collectors.toSet()));
        allNormalizedNames.addAll(context.targetTableNames.stream().map(normalizer).collect(Collectors.toSet()));

        DiffSummary summary = DiffSummary.builder()
                .totalTables(allNormalizedNames.size() + excluded)
                .tablesOnlyInSource(stats.onlyInSource)
                .tablesOnlyInTarget(stats.onlyInTarget)
                .modifiedTables(stats.modified)
                .unchangedTables(stats.unchanged)
                .excludedDeprecatedTables(excluded)
                .build();
        List<TableDiff> changedTableDiffs = tableDiffs.stream()
                .filter(tableDiff -> tableDiff.getDiffType() != TableDiffType.UNCHANGED)
                .collect(Collectors.toList());

        String sourceKey = param.getSourceDataSourceId() + "." + param.getSourceDatabaseName()
                + (param.getSourceSchemaName() != null ? "." + param.getSourceSchemaName() : "");
        String targetKey = param.getTargetDataSourceId() + "." + param.getTargetDatabaseName()
                + (param.getTargetSchemaName() != null ? "." + param.getTargetSchemaName() : "");

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("Schema compare completed in {} ms. Total tables: {}, Added: {}, Removed: {}, Modified: {}, Unchanged: {}, Returned: {}",
                totalTime, summary.getTotalTables(), stats.onlyInSource, stats.onlyInTarget,
                stats.modified, stats.unchanged, changedTableDiffs.size());

        return SchemaDiffResult.builder()
                .sourceKey(sourceKey)
                .targetKey(targetKey)
                .summary(summary)
                .tableDiffs(changedTableDiffs)
                .build();
    }

    /**
     * Execute DDL migration statements on the target database.
     * Supports transaction mode and continue-on-error mode.
     * 
     * @param param migration parameters including DDL statements and execution options
     * @return migration result with per-statement execution status
     */
    @Override
    public MigrateResult migrate(SchemaMigrateParam param) {
        if (CollectionUtils.isEmpty(param.getDdlStatements())) {
            return MigrateResult.builder()
                    .success(true)
                    .totalStatements(0)
                    .successCount(0)
                    .failCount(0)
                    .statementResults(Collections.emptyList())
                    .build();
        }

        Connection conn = null;
        boolean originalAutoCommit = true;
        ConnectInfo previousInfo = Chat2DBContext.getConnectInfo();
        try {
            ConnectInfo connectInfo = createConnectInfo(param.getTargetDataSourceId(), param.getTargetDatabaseName(),
                    param.getTargetSchemaName());
            Chat2DBContext.putContext(connectInfo);

            Plugin plugin = Chat2DBContext.PLUGIN_MAP.get(connectInfo.getDbType());
            conn = plugin.getDBManage().getConnection(connectInfo);
            connectInfo.setConnection(conn);

            if (param.isExecuteInTransaction()) {
                originalAutoCommit = conn.getAutoCommit();
                conn.setAutoCommit(false);
            }

            ai.chat2db.spi.CommandExecutor executor = plugin.getMetaData().getCommandExecutor();

            List<MigrationStatementResult> results = new ArrayList<>();
            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < param.getDdlStatements().size(); i++) {
                String sql = param.getDdlStatements().get(i);
                if (StringUtils.isBlank(sql)) {
                    continue;
                }
                long start = System.currentTimeMillis();
                try {
                    ai.chat2db.spi.model.Command command = new ai.chat2db.spi.model.Command();
                    command.setScript(sql);
                    List<ai.chat2db.spi.model.ExecuteResult> executeResults = executor.execute(command);
                    long duration = System.currentTimeMillis() - start;
                    boolean statementSuccess = executeResults != null
                            && executeResults.stream().allMatch(r -> r.getSuccess() != null && r.getSuccess());
                    if (statementSuccess) {
                        successCount++;
                    } else {
                        failCount++;
                        if (!param.isContinueOnError()) {
                            String msg = executeResults != null && !executeResults.isEmpty()
                                    ? executeResults.get(0).getMessage()
                                    : "Unknown error";
                            results.add(MigrationStatementResult.builder()
                                    .sequence(i + 1)
                                    .sql(sql)
                                    .success(false)
                                    .errorMessage(msg)
                                    .duration(duration)
                                    .build());
                            if (param.isExecuteInTransaction()) {
                                conn.rollback();
                            }
                            break;
                        }
                    }
                    results.add(MigrationStatementResult.builder()
                            .sequence(i + 1)
                            .sql(sql)
                            .success(statementSuccess)
                            .errorMessage(statementSuccess ? null : (executeResults != null && !executeResults.isEmpty()
                                    ? executeResults.get(0).getMessage() : "Unknown error"))
                            .duration(duration)
                            .build());
                } catch (Exception e) {
                    long duration = System.currentTimeMillis() - start;
                    failCount++;
                    results.add(MigrationStatementResult.builder()
                            .sequence(i + 1)
                            .sql(sql)
                            .success(false)
                            .errorMessage(e.getMessage())
                            .duration(duration)
                            .build());
                    if (param.isExecuteInTransaction()) {
                        conn.rollback();
                    }
                    if (!param.isContinueOnError()) {
                        break;
                    }
                }
            }

            if (param.isExecuteInTransaction() && failCount == 0) {
                conn.commit();
            }

            return MigrateResult.builder()
                    .success(failCount == 0)
                    .statementResults(results)
                    .totalStatements(results.size())
                    .successCount(successCount)
                    .failCount(failCount)
                    .build();
        } catch (SQLException e) {
            log.error("Migration failed with SQL error", e);
            throw new RuntimeException("Migration failed: " + e.getMessage(), e);
        } finally {
            if (conn != null && param.isExecuteInTransaction()) {
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException e) {
                    log.warn("Failed to restore auto-commit", e);
                }
            }
            closeQuietly(conn);
            if (previousInfo != null) {
                Chat2DBContext.putContext(previousInfo);
            } else {
                Chat2DBContext.remove();
            }
        }
    }

    /**
     * Get database type from data source.
     */
    private String getDbType(Long dataSourceId) {
        DataSource ds = dataSourceService.queryById(dataSourceId);
        return ds.getType();
    }

    /**
     * Create a database connection with proper context management.
     * Sets up Chat2DBContext for the plugin to access connection info.
     */
    private Connection createConnection(Long dataSourceId, String databaseName, String schemaName) {
        ConnectInfo connectInfo = createConnectInfo(dataSourceId, databaseName, schemaName);
        Plugin plugin = Chat2DBContext.PLUGIN_MAP.get(connectInfo.getDbType());

        ConnectInfo previousInfo = Chat2DBContext.getConnectInfo();
        try {
            Chat2DBContext.putContext(connectInfo);
            return plugin.getDBManage().getConnection(connectInfo);
        } finally {
            if (previousInfo != null) {
                Chat2DBContext.putContext(previousInfo);
            } else {
                Chat2DBContext.remove();
            }
        }
    }

    private ConnectInfo createConnectInfo(Long dataSourceId, String databaseName, String schemaName) {
        DataSource dataSource = dataSourceService.queryById(dataSourceId);
        if (dataSource == null) {
            throw new RuntimeException("DataSource not found: " + dataSourceId);
        }
        dataSourceAccessBusinessService.checkPermission(dataSource);

        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(dataSourceId);
        connectInfo.setUser(dataSource.getUserName());
        connectInfo.setPassword(dataSource.getPassword());
        connectInfo.setDbType(dataSource.getType());
        connectInfo.setUrl(dataSource.getUrl());
        connectInfo.setDatabase(databaseName);
        connectInfo.setSchemaName(schemaName);
        connectInfo.setDriver(dataSource.getDriver());
        connectInfo.setSsh(dataSource.getSsh());
        connectInfo.setSsl(dataSource.getSsl());
        connectInfo.setJdbc(dataSource.getJdbc());
        connectInfo.setExtendInfo(dataSource.getExtendInfo());
        connectInfo.setHost(dataSource.getHost());
        if (StringUtils.isNotBlank(dataSource.getPort())) {
            connectInfo.setPort(Integer.parseInt(dataSource.getPort()));
        }
        ai.chat2db.spi.config.DriverConfig driverConfig = dataSource.getDriverConfig();
        if (driverConfig == null) {
            driverConfig = Chat2DBContext.getDefaultDriverConfig(dataSource.getType());
        }
        connectInfo.setDriverConfig(driverConfig);
        connectInfo.setConsoleOwn(false);
        return connectInfo;
    }

    /**
     * Query deprecated table names for exclusion during comparison.
     */
    private Set<String> queryDeprecatedTableNames(Long dataSourceId, String databaseName, String schemaName) {
        DeprecatedTableParam param = new DeprecatedTableParam();
        param.setUserId(ContextUtils.getUserId());
        param.setDataSourceId(dataSourceId);
        param.setDatabaseName(databaseName);
        param.setSchemaName(schemaName);
        List<String> list = deprecatedTableService.queryDeprecatedTables(param);
        return new HashSet<>(list);
    }

    /**
     * List table names from database metadata, filtering by specific tables and deprecated set.
     */
    private List<String> listTableNames(Connection conn, MetaData meta, String databaseName, String schemaName,
                                         List<String> specificTables, Set<String> deprecatedSet) {
        List<Table> tables = meta.tables(conn, databaseName, schemaName, null);
        if (CollectionUtils.isEmpty(tables)) {
            return Collections.emptyList();
        }
        return tables.stream()
                .map(Table::getName)
                .filter(name -> CollectionUtils.isEmpty(specificTables) || specificTables.contains(name))
                .filter(name -> !deprecatedSet.contains(name))
                .collect(Collectors.toList());
    }

    /**
     * 批量获取所有表的完整详情（列、索引、外键）。
     * 核心优化：通过一次查询获取所有表的元数据，避免 N+1 查询问题。
     * 优化前：每个表 4 次查询（表信息 + 列 + 索引 + 外键），N 个表 = 4N 次查询
     * 优化后：每个类型 1 次查询，总共 4 次查询（表 + 列 + 索引 + 外键）
     * 
     * @param conn 数据库连接
     * @param meta 元数据访问接口
     * @param databaseName 数据库名
     * @param schemaName 模式名
     * @param tableNames 需要获取详情的表名列表
     * @return Map<表名, 表详情>
     */
    private Map<String, Table> batchFetchTableDetails(Connection conn, MetaData meta, 
                                                       String databaseName, String schemaName,
                                                       List<String> tableNames) {
        if (CollectionUtils.isEmpty(tableNames)) {
            return Collections.emptyMap();
        }

        Map<String, Table> tableMap = new HashMap<>();
        
        // 第一步：批量获取所有表的基本信息
        try {
            List<Table> tables = meta.tables(conn, databaseName, schemaName, null);
            if (CollectionUtils.isNotEmpty(tables)) {
                for (Table table : tables) {
                    if (tableNames.contains(table.getName())) {
                        tableMap.put(table.getName(), table);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to batch fetch tables: {}", e.getMessage());
        }

        if (tableMap.isEmpty()) {
            return Collections.emptyMap();
        }

        // 第二步：批量获取所有列并按表名分组
        try {
            List<TableColumn> allColumns = meta.columns(conn, databaseName, schemaName, null);
            if (CollectionUtils.isNotEmpty(allColumns)) {
                Map<String, List<TableColumn>> columnsByTable = allColumns.stream()
                        .filter(col -> col.getTableName() != null)
                        .collect(Collectors.groupingBy(TableColumn::getTableName));
                
                for (Map.Entry<String, Table> entry : tableMap.entrySet()) {
                    List<TableColumn> columns = columnsByTable.getOrDefault(entry.getKey(), Collections.emptyList());
                    entry.getValue().setColumnList(columns);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to batch fetch columns: {}", e.getMessage());
            // 如果批量获取失败，逐个表获取作为降级方案
            for (String tableName : tableNames) {
                Table table = tableMap.get(tableName);
                if (table != null) {
                    try {
                        table.setColumnList(meta.columns(conn, databaseName, schemaName, tableName));
                    } catch (Exception ex) {
                        log.warn("Failed to fetch columns for table {}: {}", tableName, ex.getMessage());
                        table.setColumnList(Collections.emptyList());
                    }
                }
            }
        }

        // 第三步：批量获取所有索引并按表名分组
        try {
            List<TableIndex> allIndexes = meta.indexes(conn, databaseName, schemaName, null);
            if (CollectionUtils.isNotEmpty(allIndexes)) {
                Map<String, List<TableIndex>> indexesByTable = allIndexes.stream()
                        .filter(idx -> idx.getTableName() != null)
                        .collect(Collectors.groupingBy(TableIndex::getTableName));
                
                for (Map.Entry<String, Table> entry : tableMap.entrySet()) {
                    List<TableIndex> indexes = indexesByTable.getOrDefault(entry.getKey(), Collections.emptyList());
                    entry.getValue().setIndexList(indexes);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to batch fetch indexes: {}", e.getMessage());
            for (String tableName : tableNames) {
                Table table = tableMap.get(tableName);
                if (table != null) {
                    try {
                        table.setIndexList(meta.indexes(conn, databaseName, schemaName, tableName));
                    } catch (Exception ex) {
                        log.warn("Failed to fetch indexes for table {}: {}", tableName, ex.getMessage());
                        table.setIndexList(Collections.emptyList());
                    }
                }
            }
        }

        // 第四步：批量获取所有外键并按表名分组
        try {
            List<ForeignKey> allFKs = meta.foreignKeys(conn, databaseName, schemaName, null);
            if (CollectionUtils.isNotEmpty(allFKs)) {
                Map<String, List<ForeignKey>> fksByTable = allFKs.stream()
                        .filter(fk -> fk.getTableName() != null)
                        .collect(Collectors.groupingBy(ForeignKey::getTableName));
                
                for (Map.Entry<String, Table> entry : tableMap.entrySet()) {
                    List<ForeignKey> fks = fksByTable.getOrDefault(entry.getKey(), Collections.emptyList());
                    entry.getValue().setForeignKeyList(fks);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to batch fetch foreign keys: {}", e.getMessage());
            for (String tableName : tableNames) {
                Table table = tableMap.get(tableName);
                if (table != null) {
                    try {
                        table.setForeignKeyList(meta.foreignKeys(conn, databaseName, schemaName, tableName));
                    } catch (Exception ex) {
                        log.warn("Failed to fetch foreign keys for table {}: {}", tableName, ex.getMessage());
                        table.setForeignKeyList(Collections.emptyList());
                    }
                }
            }
        }

        // 确保所有表都有非空的列表
        for (Table table : tableMap.values()) {
            if (table.getColumnList() == null) {
                table.setColumnList(Collections.emptyList());
            }
            if (table.getIndexList() == null) {
                table.setIndexList(Collections.emptyList());
            }
            if (table.getForeignKeyList() == null) {
                table.setForeignKeyList(Collections.emptyList());
            }
        }

        return tableMap;
    }

    /**
     * Fetch complete table details including columns, indexes, and foreign keys.
     * Handles fetch errors gracefully by returning empty lists.
     * 
     * @deprecated 使用 {@link #batchFetchTableDetails} 替代，批量查询性能更优
     */
    @Deprecated
    private Table fetchTableDetails(Connection conn, MetaData meta, String databaseName, String schemaName,
                                      String tableName) {
        List<Table> tables = meta.tables(conn, databaseName, schemaName, tableName);
        if (CollectionUtils.isEmpty(tables)) {
            return null;
        }
        Table table = tables.get(0);
        try {
            table.setColumnList(meta.columns(conn, databaseName, schemaName, tableName));
        } catch (Exception e) {
            log.warn("Failed to fetch columns for table {}: {}", tableName, e.getMessage());
            table.setColumnList(Collections.emptyList());
        }
        try {
            table.setIndexList(meta.indexes(conn, databaseName, schemaName, tableName));
        } catch (Exception e) {
            log.warn("Failed to fetch indexes for table {}: {}", tableName, e.getMessage());
            table.setIndexList(Collections.emptyList());
        }
        try {
            table.setForeignKeyList(meta.foreignKeys(conn, databaseName, schemaName, tableName));
        } catch (Exception e) {
            log.warn("Failed to fetch foreign keys for table {}: {}", tableName, e.getMessage());
            table.setForeignKeyList(Collections.emptyList());
        }
        return table;
    }

    /**
     * Compare columns between source and target tables.
     * 
     * @param caseSensitive whether to use case-sensitive name matching
     * @return list of column differences with change types
     */
    private List<ColumnDiff> compareColumns(List<TableColumn> sourceCols, List<TableColumn> targetCols,
                                            CompareOption option) {
        List<ColumnDiff> diffs = new ArrayList<>();
        if (CollectionUtils.isEmpty(sourceCols) && CollectionUtils.isEmpty(targetCols)) {
            return diffs;
        }
        boolean caseSensitive = option.isCaseSensitive();
        Map<String, TableColumn> sourceMap = CollectionUtils.isEmpty(sourceCols)
                ? Collections.emptyMap()
                : sourceCols.stream().filter(c -> c.getName() != null)
                        .collect(Collectors.toMap(c -> caseSensitive ? c.getName() : c.getName().toLowerCase(), c -> c, (a, b) -> a));
        Map<String, TableColumn> targetMap = CollectionUtils.isEmpty(targetCols)
                ? Collections.emptyMap()
                : targetCols.stream().filter(c -> c.getName() != null)
                        .collect(Collectors.toMap(c -> caseSensitive ? c.getName() : c.getName().toLowerCase(), c -> c, (a, b) -> a));

        for (Map.Entry<String, TableColumn> entry : targetMap.entrySet()) {
            String colName = entry.getKey();
            TableColumn targetCol = entry.getValue();
            TableColumn sourceCol = sourceMap.get(colName);
            if (sourceCol == null) {
                diffs.add(ColumnDiff.builder()
                        .changeType(EditStatus.ADD)
                        .targetColumn(targetCol)
                        .changedFields(Collections.singletonList(fieldDiff("targetOnly", null, targetCol.getName())))
                        .build());
            } else if (!columnEquals(sourceCol, targetCol, option)) {
                diffs.add(ColumnDiff.builder()
                        .changeType(EditStatus.MODIFY)
                        .sourceColumn(sourceCol)
                        .targetColumn(targetCol)
                        .changedFields(describeColumnDiff(sourceCol, targetCol, option))
                        .build());
            }
        }
        for (Map.Entry<String, TableColumn> entry : sourceMap.entrySet()) {
            if (!targetMap.containsKey(entry.getKey())) {
                diffs.add(ColumnDiff.builder()
                        .changeType(EditStatus.DELETE)
                        .sourceColumn(entry.getValue())
                        .changedFields(Collections.singletonList(fieldDiff("sourceOnly", entry.getValue().getName(), null)))
                        .build());
            }
        }
        return diffs;
    }

    private boolean columnEquals(TableColumn a, TableColumn b, CompareOption option) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.getDataType(), b.getDataType())
                && columnSizeEquals(a, b, option)
                && Objects.equals(a.getDecimalDigits(), b.getDecimalDigits())
                && Objects.equals(a.getNullable(), b.getNullable())
                && Objects.equals(a.getDefaultValue(), b.getDefaultValue())
                && Objects.equals(a.getComment(), b.getComment())
                && Objects.equals(a.getAutoIncrement(), b.getAutoIncrement())
                && charsetEquals(a.getCharSetName(), b.getCharSetName(), option)
                && collationEquals(a.getCollationName(), b.getCollationName(), option)
                && columnTypeEquals(a.getColumnType(), b.getColumnType(), option);
    }

    private List<FieldDiff> describeColumnDiff(TableColumn source, TableColumn target, CompareOption option) {
        List<FieldDiff> changedFields = new ArrayList<>();
        addChangedField(changedFields, "dataType", source.getDataType(), target.getDataType());
        addChangedField(changedFields, "columnSize", source.getColumnSize(), target.getColumnSize(),
                (sourceValue, targetValue) -> columnSizeEquals(source, target, option));
        addChangedField(changedFields, "decimalDigits", source.getDecimalDigits(), target.getDecimalDigits());
        addChangedField(changedFields, "nullable", source.getNullable(), target.getNullable());
        addChangedField(changedFields, "defaultValue", source.getDefaultValue(), target.getDefaultValue());
        addChangedField(changedFields, "comment", source.getComment(), target.getComment());
        addChangedField(changedFields, "autoIncrement", source.getAutoIncrement(), target.getAutoIncrement());
        addChangedField(changedFields, "charSetName", source.getCharSetName(), target.getCharSetName(),
                (sourceValue, targetValue) -> charsetEquals(sourceValue, targetValue, option));
        addChangedField(changedFields, "collationName", source.getCollationName(), target.getCollationName(),
                (sourceValue, targetValue) -> collationEquals(sourceValue, targetValue, option));
        addChangedField(changedFields, "columnType", source.getColumnType(), target.getColumnType(),
                (sourceValue, targetValue) -> columnTypeEquals(sourceValue, targetValue, option));
        return changedFields;
    }

    private boolean columnSizeEquals(TableColumn source, TableColumn target, CompareOption option) {
        if (option.isIgnoreIntegerDisplayWidth() && isMysqlIntegerType(source.getDataType(), source.getColumnType())
                && isMysqlIntegerType(target.getDataType(), target.getColumnType())) {
            return true;
        }
        return Objects.equals(source.getColumnSize(), target.getColumnSize());
    }

    private boolean columnTypeEquals(String sourceValue, String targetValue, CompareOption option) {
        if (!option.isIgnoreIntegerDisplayWidth()) {
            return Objects.equals(sourceValue, targetValue);
        }
        return Objects.equals(normalizeMysqlIntegerDisplayWidth(sourceValue),
                normalizeMysqlIntegerDisplayWidth(targetValue));
    }

    private boolean isMysqlIntegerType(String dataType, String columnType) {
        String type = StringUtils.defaultIfBlank(dataType, columnType);
        if (StringUtils.isBlank(type)) {
            return false;
        }
        String normalized = StringUtils.substringBefore(type, "(").trim();
        normalized = StringUtils.substringBefore(normalized, " ").trim();
        return StringUtils.equalsAnyIgnoreCase(normalized,
                "tinyint", "smallint", "mediumint", "int", "integer", "bigint");
    }

    private String normalizeMysqlIntegerDisplayWidth(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        java.util.regex.Matcher matcher = MYSQL_INTEGER_COLUMN_TYPE_WITH_WIDTH.matcher(value.trim());
        if (!matcher.matches()) {
            return value;
        }
        return matcher.group(1).toLowerCase() + StringUtils.defaultString(matcher.group(2)).toLowerCase();
    }

    private boolean charsetEquals(String sourceValue, String targetValue, CompareOption option) {
        if (!option.isIgnoreCharsetAlias()) {
            return Objects.equals(sourceValue, targetValue);
        }
        return Objects.equals(normalizeCharsetAlias(sourceValue), normalizeCharsetAlias(targetValue));
    }

    private boolean collationEquals(String sourceValue, String targetValue, CompareOption option) {
        if (!option.isIgnoreCharsetAlias()) {
            return Objects.equals(sourceValue, targetValue);
        }
        return Objects.equals(normalizeCollationAlias(sourceValue), normalizeCollationAlias(targetValue));
    }

    private String normalizeCharsetAlias(String value) {
        if ("utf8".equalsIgnoreCase(value)) {
            return "utf8mb3";
        }
        return value;
    }

    private String normalizeCollationAlias(String value) {
        if (StringUtils.startsWithIgnoreCase(value, "utf8_")) {
            return "utf8mb3_" + value.substring("utf8_".length());
        }
        return value;
    }

    private String getColumnDiffName(ColumnDiff diff) {
        if (diff.getTargetColumn() != null) {
            return diff.getTargetColumn().getName();
        }
        return diff.getSourceColumn() == null ? null : diff.getSourceColumn().getName();
    }

    /**
     * Compare indexes between source and target tables.
     * 
     * @param caseSensitive whether to use case-sensitive name matching
     * @return list of index differences with change types
     */
    private List<IndexDiff> compareIndexes(List<TableIndex> sourceIdxs, List<TableIndex> targetIdxs, boolean caseSensitive) {
        List<IndexDiff> diffs = new ArrayList<>();
        if (CollectionUtils.isEmpty(sourceIdxs) && CollectionUtils.isEmpty(targetIdxs)) {
            return diffs;
        }
        Map<String, TableIndex> sourceMap = CollectionUtils.isEmpty(sourceIdxs)
                ? Collections.emptyMap()
                : sourceIdxs.stream().filter(i -> i.getName() != null)
                        .collect(Collectors.toMap(i -> caseSensitive ? i.getName() : i.getName().toLowerCase(), i -> i, (a, b) -> a));
        Map<String, TableIndex> targetMap = CollectionUtils.isEmpty(targetIdxs)
                ? Collections.emptyMap()
                : targetIdxs.stream().filter(i -> i.getName() != null)
                        .collect(Collectors.toMap(i -> caseSensitive ? i.getName() : i.getName().toLowerCase(), i -> i, (a, b) -> a));

        for (Map.Entry<String, TableIndex> entry : targetMap.entrySet()) {
            String idxName = entry.getKey();
            TableIndex targetIdx = entry.getValue();
            TableIndex sourceIdx = sourceMap.get(idxName);
            if (sourceIdx == null) {
                diffs.add(IndexDiff.builder()
                        .changeType(EditStatus.ADD)
                        .targetIndex(targetIdx)
                        .changedFields(Collections.singletonList(fieldDiff("targetOnly", null, targetIdx.getName())))
                        .build());
            } else if (!indexEquals(sourceIdx, targetIdx)) {
                diffs.add(IndexDiff.builder()
                        .changeType(EditStatus.MODIFY)
                        .sourceIndex(sourceIdx)
                        .targetIndex(targetIdx)
                        .changedFields(describeIndexDiff(sourceIdx, targetIdx))
                        .build());
            }
        }
        for (Map.Entry<String, TableIndex> entry : sourceMap.entrySet()) {
            if (!targetMap.containsKey(entry.getKey())) {
                diffs.add(IndexDiff.builder()
                        .changeType(EditStatus.DELETE)
                        .sourceIndex(entry.getValue())
                        .changedFields(Collections.singletonList(fieldDiff("sourceOnly", entry.getValue().getName(), null)))
                        .build());
            }
        }
        return diffs;
    }

    private boolean indexEquals(TableIndex a, TableIndex b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.getType(), b.getType())
                && Objects.equals(a.getUnique(), b.getUnique())
                && Objects.equals(a.getMethod(), b.getMethod())
                && Objects.equals(a.getComment(), b.getComment());
    }

    private List<FieldDiff> describeIndexDiff(TableIndex source, TableIndex target) {
        List<FieldDiff> changedFields = new ArrayList<>();
        addChangedField(changedFields, "type", source.getType(), target.getType());
        addChangedField(changedFields, "unique", source.getUnique(), target.getUnique());
        addChangedField(changedFields, "method", source.getMethod(), target.getMethod());
        addChangedField(changedFields, "comment", source.getComment(), target.getComment());
        return changedFields;
    }

    private String getIndexDiffName(IndexDiff diff) {
        if (diff.getTargetIndex() != null) {
            return diff.getTargetIndex().getName();
        }
        return diff.getSourceIndex() == null ? null : diff.getSourceIndex().getName();
    }

    /**
     * Compare foreign keys between source and target tables.
     * 
     * @param caseSensitive whether to use case-sensitive name matching
     * @return list of foreign key differences with change types
     */
    private List<ForeignKeyDiff> compareForeignKeys(List<ForeignKey> sourceFKs, List<ForeignKey> targetFKs, boolean caseSensitive) {
        List<ForeignKeyDiff> diffs = new ArrayList<>();
        if (CollectionUtils.isEmpty(sourceFKs) && CollectionUtils.isEmpty(targetFKs)) {
            return diffs;
        }
        Map<String, ForeignKey> sourceMap = CollectionUtils.isEmpty(sourceFKs)
                ? Collections.emptyMap()
                : sourceFKs.stream().filter(fk -> fk.getName() != null)
                        .collect(Collectors.toMap(fk -> caseSensitive ? fk.getName() : fk.getName().toLowerCase(), fk -> fk, (a, b) -> a));
        Map<String, ForeignKey> targetMap = CollectionUtils.isEmpty(targetFKs)
                ? Collections.emptyMap()
                : targetFKs.stream().filter(fk -> fk.getName() != null)
                        .collect(Collectors.toMap(fk -> caseSensitive ? fk.getName() : fk.getName().toLowerCase(), fk -> fk, (a, b) -> a));

        for (Map.Entry<String, ForeignKey> entry : targetMap.entrySet()) {
            String fkName = entry.getKey();
            ForeignKey targetFK = entry.getValue();
            ForeignKey sourceFK = sourceMap.get(fkName);
            if (sourceFK == null) {
                diffs.add(ForeignKeyDiff.builder()
                        .changeType(EditStatus.ADD)
                        .targetForeignKey(targetFK)
                        .changedFields(Collections.singletonList(fieldDiff("targetOnly", null, targetFK.getName())))
                        .build());
            } else if (!foreignKeyEquals(sourceFK, targetFK)) {
                diffs.add(ForeignKeyDiff.builder()
                        .changeType(EditStatus.MODIFY)
                        .sourceForeignKey(sourceFK)
                        .targetForeignKey(targetFK)
                        .changedFields(describeForeignKeyDiff(sourceFK, targetFK))
                        .build());
            }
        }
        for (Map.Entry<String, ForeignKey> entry : sourceMap.entrySet()) {
            if (!targetMap.containsKey(entry.getKey())) {
                diffs.add(ForeignKeyDiff.builder()
                        .changeType(EditStatus.DELETE)
                        .sourceForeignKey(entry.getValue())
                        .changedFields(Collections.singletonList(fieldDiff("sourceOnly", entry.getValue().getName(), null)))
                        .build());
            }
        }
        return diffs;
    }

    private boolean foreignKeyEquals(ForeignKey a, ForeignKey b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.getColumn(), b.getColumn())
                && Objects.equals(a.getReferencedTable(), b.getReferencedTable())
                && Objects.equals(a.getReferencedColumn(), b.getReferencedColumn())
                && Objects.equals(a.getUpdateRule(), b.getUpdateRule())
                && Objects.equals(a.getDeleteRule(), b.getDeleteRule());
    }

    private List<FieldDiff> describeForeignKeyDiff(ForeignKey source, ForeignKey target) {
        List<FieldDiff> changedFields = new ArrayList<>();
        addChangedField(changedFields, "column", source.getColumn(), target.getColumn());
        addChangedField(changedFields, "referencedTable", source.getReferencedTable(), target.getReferencedTable());
        addChangedField(changedFields, "referencedColumn", source.getReferencedColumn(), target.getReferencedColumn());
        addChangedField(changedFields, "updateRule", source.getUpdateRule(), target.getUpdateRule());
        addChangedField(changedFields, "deleteRule", source.getDeleteRule(), target.getDeleteRule());
        return changedFields;
    }

    private String getForeignKeyDiffName(ForeignKeyDiff diff) {
        if (diff.getTargetForeignKey() != null) {
            return diff.getTargetForeignKey().getName();
        }
        return diff.getSourceForeignKey() == null ? null : diff.getSourceForeignKey().getName();
    }

    private boolean tableOptionsEqual(Table a, Table b, CompareOption option) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        boolean autoIncrementEquals = option.isIgnoreAutoIncrement()
                || Objects.equals(a.getIncrementValue(), b.getIncrementValue());
        return Objects.equals(a.getComment(), b.getComment())
                && Objects.equals(a.getEngine(), b.getEngine())
                && Objects.equals(a.getCharset(), b.getCharset())
                && Objects.equals(a.getCollate(), b.getCollate())
                && autoIncrementEquals;
    }

    private List<FieldDiff> describeTableOptionDiff(Table source, Table target, CompareOption option) {
        List<FieldDiff> changedFields = new ArrayList<>();
        addChangedField(changedFields, "comment", source.getComment(), target.getComment());
        addChangedField(changedFields, "engine", source.getEngine(), target.getEngine());
        addChangedField(changedFields, "charset", source.getCharset(), target.getCharset());
        addChangedField(changedFields, "collate", source.getCollate(), target.getCollate());
        if (!option.isIgnoreAutoIncrement()) {
            addChangedField(changedFields, "incrementValue", source.getIncrementValue(), target.getIncrementValue());
        }
        return changedFields;
    }

    private void addChangedField(List<FieldDiff> changedFields, String fieldName, Object sourceValue, Object targetValue) {
        if (!Objects.equals(sourceValue, targetValue)) {
            changedFields.add(fieldDiff(fieldName, sourceValue, targetValue));
        }
    }

    private <T> void addChangedField(List<FieldDiff> changedFields, String fieldName, T sourceValue, T targetValue,
                                     BiPredicate<T, T> equalsPredicate) {
        if (!equalsPredicate.test(sourceValue, targetValue)) {
            changedFields.add(fieldDiff(fieldName, sourceValue, targetValue));
        }
    }

    private FieldDiff fieldDiff(String fieldName, Object sourceValue, Object targetValue) {
        return FieldDiff.builder()
                .fieldName(fieldName)
                .sourceValue(sourceValue == null ? null : String.valueOf(sourceValue))
                .targetValue(targetValue == null ? null : String.valueOf(targetValue))
                .build();
    }

    private String formatChangedFields(List<FieldDiff> changedFields) {
        if (CollectionUtils.isEmpty(changedFields)) {
            return "";
        }
        return changedFields.stream()
                .map(fieldDiff -> fieldDiff.getFieldName() + ": source=" + fieldDiff.getSourceValue()
                        + ", target=" + fieldDiff.getTargetValue())
                .collect(Collectors.joining(", "));
    }

    /**
     * Build a target table with edit status flags set based on diff results.
     * Used for generating ALTER TABLE DDL statements.
     * 
     * @param caseSensitive whether to use case-sensitive name matching
     */
    private Table buildTableWithEditStatus(Table sourceTable, Table targetTable,
                                            List<ColumnDiff> columnDiffs, List<IndexDiff> indexDiffs,
                                            List<ForeignKeyDiff> fkDiffs, CompareOption option) {
        boolean caseSensitive = option.isCaseSensitive();
        Table result = new Table();
        result.setName(shouldPreserveTargetName(sourceTable, targetTable, caseSensitive)
                ? targetTable.getName() : sourceTable.getName());
        result.setComment(sourceTable.getComment());
        result.setDatabaseName(targetTable.getDatabaseName());
        result.setSchemaName(targetTable.getSchemaName());
        result.setEngine(sourceTable.getEngine());
        result.setCharset(sourceTable.getCharset());
        result.setCollate(sourceTable.getCollate());
        result.setIncrementValue(option.isIgnoreAutoIncrement()
                ? targetTable.getIncrementValue() : sourceTable.getIncrementValue());
        result.setPartition(sourceTable.getPartition());

        if (CollectionUtils.isEmpty(columnDiffs)) {
            result.setColumnList(copyColumns(sourceTable.getColumnList()));
        } else {
            List<TableColumn> columns = new ArrayList<>();
            if (sourceTable.getColumnList() != null) {
                Map<String, ColumnDiff> sourceDiffMap = columnDiffs.stream()
                        .filter(d -> d.getChangeType() == EditStatus.DELETE || d.getChangeType() == EditStatus.MODIFY)
                        .filter(d -> d.getSourceColumn() != null && d.getSourceColumn().getName() != null)
                        .collect(Collectors.toMap(d -> caseSensitive ? d.getSourceColumn().getName()
                                : d.getSourceColumn().getName().toLowerCase(), d -> d, (a, b) -> a));

                for (TableColumn sourceCol : sourceTable.getColumnList()) {
                    TableColumn col = copyColumn(sourceCol);
                    String key = caseSensitive ? col.getName() : col.getName().toLowerCase();
                    ColumnDiff diff = sourceDiffMap.get(key);
                    if (diff != null) {
                        col.setEditStatus(diff.getChangeType() == EditStatus.DELETE
                                ? EditStatus.ADD.name() : EditStatus.MODIFY.name());
                        if (diff.getChangeType() == EditStatus.MODIFY && diff.getTargetColumn() != null
                                && !Objects.equals(diff.getTargetColumn().getName(), col.getName())) {
                            col.setOldName(diff.getTargetColumn().getName());
                        }
                    }
                    columns.add(col);
                }
            }
            for (ColumnDiff diff : columnDiffs) {
                if (diff.getChangeType() == EditStatus.ADD && diff.getTargetColumn() != null) {
                    TableColumn deletedCol = copyColumn(diff.getTargetColumn());
                    deletedCol.setEditStatus(EditStatus.DELETE.name());
                    columns.add(deletedCol);
                }
            }
            result.setColumnList(columns);
        }

        if (CollectionUtils.isEmpty(indexDiffs)) {
            result.setIndexList(copyIndexes(sourceTable.getIndexList()));
        } else {
            List<TableIndex> indexes = new ArrayList<>();
            if (sourceTable.getIndexList() != null) {
                Map<String, IndexDiff> sourceDiffMap = indexDiffs.stream()
                        .filter(d -> d.getChangeType() == EditStatus.DELETE || d.getChangeType() == EditStatus.MODIFY)
                        .filter(d -> d.getSourceIndex() != null && d.getSourceIndex().getName() != null)
                        .collect(Collectors.toMap(d -> caseSensitive ? d.getSourceIndex().getName()
                                : d.getSourceIndex().getName().toLowerCase(), d -> d, (a, b) -> a));
                for (TableIndex sourceIdx : sourceTable.getIndexList()) {
                    TableIndex idx = copyIndex(sourceIdx);
                    String key = caseSensitive ? idx.getName() : idx.getName().toLowerCase();
                    IndexDiff diff = sourceDiffMap.get(key);
                    if (diff != null) {
                        idx.setEditStatus(diff.getChangeType() == EditStatus.DELETE
                                ? EditStatus.ADD.name() : EditStatus.MODIFY.name());
                        if (diff.getChangeType() == EditStatus.MODIFY && diff.getTargetIndex() != null) {
                            idx.setOldName(diff.getTargetIndex().getName());
                        }
                    }
                    indexes.add(idx);
                }
            }
            for (IndexDiff diff : indexDiffs) {
                if (diff.getChangeType() == EditStatus.ADD && diff.getTargetIndex() != null) {
                    TableIndex deletedIdx = copyIndex(diff.getTargetIndex());
                    deletedIdx.setOldName(diff.getTargetIndex().getName());
                    deletedIdx.setEditStatus(EditStatus.DELETE.name());
                    indexes.add(deletedIdx);
                }
            }
            result.setIndexList(indexes);
        }

        result.setForeignKeyList(copyForeignKeys(sourceTable.getForeignKeyList()));

        return result;
    }

    private boolean shouldPreserveTargetName(Table sourceTable, Table targetTable, boolean caseSensitive) {
        return !caseSensitive && StringUtils.equalsIgnoreCase(sourceTable.getName(), targetTable.getName());
    }

    private List<TableColumn> copyColumns(List<TableColumn> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        return source.stream().map(this::copyColumn).collect(Collectors.toList());
    }

    private TableColumn copyColumn(TableColumn source) {
        TableColumn target = new TableColumn();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private List<TableIndex> copyIndexes(List<TableIndex> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        return source.stream().map(this::copyIndex).collect(Collectors.toList());
    }

    private TableIndex copyIndex(TableIndex source) {
        TableIndex target = new TableIndex();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private List<ForeignKey> copyForeignKeys(List<ForeignKey> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        return source.stream().map(this::copyForeignKey).collect(Collectors.toList());
    }

    private ForeignKey copyForeignKey(ForeignKey source) {
        ForeignKey target = new ForeignKey();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                log.warn("Error closing connection", e);
            }
        }
    }
}
