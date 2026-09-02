package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.enums.ExportSizeEnum;
import ai.chat2db.community.domain.api.enums.ExportTypeEnum;
import ai.chat2db.community.domain.api.model.db.DbDmlExportPlan;
import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.request.db.DbDmlExportRequest;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionOperation;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.extension.ExportCell;
import ai.chat2db.community.domain.api.model.task.extension.ExportCellContext;
import ai.chat2db.community.domain.api.model.task.pipeline.ExportSchema;
import ai.chat2db.community.domain.api.model.task.pipeline.FormatSink;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.service.db.IDbDmlExportService;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.SqlValueSerializer;
import ai.chat2db.community.domain.core.impl.task.export.excel.MultiSheetExcelWriter;
import ai.chat2db.community.domain.core.impl.task.export.sink.CsvSink;
import ai.chat2db.community.domain.core.impl.task.export.sink.JsonSink;
import ai.chat2db.community.domain.core.impl.task.export.sink.MarkdownSink;
import ai.chat2db.community.domain.core.impl.task.export.sink.NdjsonSink;
import ai.chat2db.community.domain.core.impl.task.export.sink.SqlSink;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.ParamBusinessException;
import ai.chat2db.community.tools.util.EasyCollectionUtils;
import ai.chat2db.community.tools.util.EasyEnumUtils;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.SqlUtils;
import cn.hutool.core.date.DatePattern;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.builder.ExcelWriterBuilder;
import com.google.common.collect.Lists;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.SpreadsheetVersion;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongConsumer;

@Service
public class DbDmlExportServiceImpl implements IDbDmlExportService {

    private final SqlExecutionPolicyManager sqlExecutionPolicyManager;

    private final ExportCellProcessorChain exportCellProcessorChain;

    public DbDmlExportServiceImpl(SqlExecutionPolicyManager sqlExecutionPolicyManager,
            ExportCellProcessorChain exportCellProcessorChain) {
        this.sqlExecutionPolicyManager = sqlExecutionPolicyManager;
        this.exportCellProcessorChain = exportCellProcessorChain;
    }

    @Override
    public String resolveTableName(String sql, String databaseName, String schemaName) {
        DbType dbType = currentDruidDbType();
        if (dbType == null) {
            return StringUtils.join(Lists.newArrayList(databaseName, schemaName), "_");
        }
        try {
            return SqlUtils.getTableName(sql, dbType);
        } catch (Exception ignored) {
            return StringUtils.join(Lists.newArrayList(databaseName, schemaName), "_");
        }
    }

    @Override
    public DbDmlExportPlan prepareExport(DbDmlExportRequest param) {
        String sql = resolveSql(param);
        ExportTypeEnum exportType = EasyEnumUtils.getEnum(ExportTypeEnum.class, param.getExportType());
        if (exportType == null) {
            throw new ParamBusinessException("exportType");
        }
        String tableName = resolveTableName(sql, param.getDatabaseName(), param.getSchemaName());
        param.setSql(sql);
        return DbDmlExportPlan.builder()
                .fileName(buildFileName(tableName))
                .exportType(exportType)
                .exportRequest(param)
                .build();
    }

    @Override
    public void export(DbDmlExportRequest param, OutputStream outputStream,
            ISqlExecutionStatementListener statementListener, Runnable cancellationChecker,
            LongConsumer exportedRowsListener, Runnable fileFinalizationListener) throws IOException {
        LongConsumer rowListener = Objects.requireNonNull(exportedRowsListener, "exportedRowsListener");
        Runnable finalizationListener = Objects.requireNonNull(fileFinalizationListener,
                "fileFinalizationListener");
        SqlExecutionPlan plan = authorizeExport(param);
        ExportTypeEnum exportType = ExportTypeEnum.from(param.getExportType());
        if (ExportTypeEnum.EXCEL == exportType) {
            exportExcel(plan, outputStream, param.getResultSetId(), statementListener, cancellationChecker,
                    rowListener, finalizationListener);
            return;
        }
        exportWithSink(param, plan, exportType, outputStream, statementListener, cancellationChecker,
                rowListener, finalizationListener);
    }

    private void exportWithSink(DbDmlExportRequest param, SqlExecutionPlan plan, ExportTypeEnum exportType,
            OutputStream outputStream, ISqlExecutionStatementListener statementListener,
            Runnable cancellationChecker, LongConsumer exportedRowsListener,
            Runnable fileFinalizationListener) throws IOException {
        boolean sqlLiteral = exportType == ExportTypeEnum.INSERT;
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        String parsedInsertTable = sqlLiteral ? defaultInsertTable(param, plan) : null;
        AtomicReference<FormatSink> sinkReference = new AtomicReference<>();
        List<Integer> includedIndexes = new ArrayList<>();
        try {
            DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), plan.getSql(), headerList -> {
                includedIndexes.addAll(sqlExecutionPolicyManager.includedColumnIndexes(plan, headerList));
                List<Header> includedHeaders = selectByListIndex(headerList, includedIndexes);
                if (sqlLiteral && includedHeaders.isEmpty()) {
                    throw new IllegalStateException("SQL export has no authorized columns");
                }
                List<String> names = includedHeaders.stream().map(Header::getName).toList();
                try {
                    FormatSink sink = createSink(exportType, outputStream, includedHeaders);
                    sink.writeSchema(new ExportSchema(names), sqlLiteral
                            ? firstHeaderOr(includedHeaders, Header::getTableName, parsedInsertTable) : "");
                    sinkReference.set(sink);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, dataList -> {
                try {
                    sinkReference.get().writeRows(
                            List.of(new ArrayList<>(selectByListIndex(dataList, includedIndexes))));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                exportedRowsListener.accept(1L);
            }, exportValueFormatter(plan, valueProcessor, sqlLiteral), false, param.getResultSetId(),
                    statementListener, cancellationChecker, plan.getMaxRows());
            fileFinalizationListener.run();
            FormatSink sink = sinkReference.get();
            if (sink != null) {
                sink.finishTable(null);
                sink.close();
            }
        } catch (UncheckedIOException e) {
            throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                    "Could not write export file", e.getCause());
        }
    }

    private FormatSink createSink(ExportTypeEnum exportType, OutputStream outputStream,
            List<Header> includedHeaders) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        return switch (exportType) {
            case CSV -> new CsvSink(outputStream, true);
            case JSON -> new JsonSink(outputStream);
            case NDJSON -> new NdjsonSink(outputStream);
            case MARKDOWN -> new MarkdownSink(outputStream);
            case INSERT -> new SqlSink(outputStream, Chat2DBContext.getSqlBuilder(),
                    firstHeaderOr(includedHeaders, Header::getDatabaseName, connectInfo.getDatabaseName()),
                    firstHeaderOr(includedHeaders, Header::getSchemaName, connectInfo.getSchemaName()));
            default -> throw new ParamBusinessException("exportType");
        };
    }

    private String defaultInsertTable(DbDmlExportRequest param, SqlExecutionPlan plan) {
        DbType dbType = currentDruidDbType();
        return dbType == null
                ? StringUtils.join(Lists.newArrayList(param.getDatabaseName(), param.getSchemaName()), "_")
                : requireSelectTableName(plan.getSql(), dbType);
    }

    private String firstHeaderOr(List<Header> headers, Function<Header, String> accessor, String fallback) {
        return headers.stream().map(accessor).filter(StringUtils::isNotBlank).findFirst().orElse(fallback);
    }

    private SqlExecutionPlan authorizeExport(DbDmlExportRequest param) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        if (connectInfo == null) {
            throw new IllegalStateException("Database connection context is required for export");
        }
        String sourceSql = param.getSql();
        SqlExecutionContext context = new SqlExecutionContext(connectInfo.getDataSourceId(),
                connectInfo.getDbType(), connectInfo.getDatabaseName(), connectInfo.getSchemaName(),
                resolveTableName(sourceSql, param.getDatabaseName(), param.getSchemaName()), sourceSql,
                SqlExecutionOperation.EXPORT, param.getExportType());
        SqlExecutionPlan plan = sqlExecutionPolicyManager.plan(context);
        sqlExecutionPolicyManager.beforeExecute(plan);
        return plan;
    }

    private DbType currentDruidDbType() {
        return JdbcUtils.parse2DruidDbType(Chat2DBContext.getConnectInfo().getDbType());
    }

    private String resolveSql(DbDmlExportRequest param) {
        ExportSizeEnum exportSize = EasyEnumUtils.getEnum(ExportSizeEnum.class, param.getExportSize());
        String sql = exportSize == ExportSizeEnum.CURRENT_PAGE && StringUtils.isNotBlank(param.getSql())
                ? param.getSql() : param.getOriginalSql();
        if (StringUtils.isBlank(sql)) {
            throw new ParamBusinessException("sql");
        }
        return sql;
    }

    private String buildFileName(String tableName) {
        return URLEncoder.encode(
                        tableName + "_" + LocalDateTime.now().format(DatePattern.PURE_DATETIME_FORMATTER),
                        StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
    }

    private void exportExcel(SqlExecutionPlan plan, OutputStream outputStream, Integer resultSetId,
            ISqlExecutionStatementListener statementListener, Runnable cancellationChecker,
            LongConsumer exportedRowsListener, Runnable fileFinalizationListener) {
        ExcelWrapper excelWrapper = new ExcelWrapper();
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        try {
            ExcelWriterBuilder excelWriterBuilder = EasyExcel.write(outputStream)
                    .charset(StandardCharsets.UTF_8)
                    .excelType(ExcelTypeEnum.XLSX);
            List<Integer> includedIndexes = new ArrayList<>();
            DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), plan.getSql(), headerList -> {
                includedIndexes.addAll(sqlExecutionPolicyManager.includedColumnIndexes(plan, headerList));
                List<List<String>> head = EasyCollectionUtils.toList(selectByListIndex(headerList, includedIndexes),
                        header -> Lists.newArrayList(header.getName()));
                excelWrapper.setExcelWriter(excelWriterBuilder.build());
                excelWrapper.setMultiSheetExcelWriter(new MultiSheetExcelWriter(excelWrapper.getExcelWriter(), head,
                        SpreadsheetVersion.EXCEL2007, "Data"));
                excelWrapper.getMultiSheetExcelWriter().initialize();
            }, dataList -> {
                excelWrapper.getMultiSheetExcelWriter().writeRow(selectByListIndex(dataList, includedIndexes));
                exportedRowsListener.accept(1L);
            }, exportValueFormatter(plan, valueProcessor, false), false, resultSetId, statementListener,
                    cancellationChecker, plan.getMaxRows());
            fileFinalizationListener.run();
        } finally {
            if (excelWrapper.getExcelWriter() != null) {
                excelWrapper.getExcelWriter().finish();
            }
        }
    }

    private Function<JDBCDataValue, String> exportValueFormatter(SqlExecutionPlan plan,
            IValueProcessor valueProcessor, boolean sqlLiteral) {
        return jdbcValue -> {
            if (exportCellProcessorChain.isEmpty()) {
                return sqlLiteral ? valueProcessor.getJdbcSqlValueString(jdbcValue)
                        : valueProcessor.getJdbcValue(jdbcValue);
            }
            ExportCell original = exportCell(jdbcValue);
            ExportCell processed = exportCellProcessorChain.process(exportCellContext(plan, jdbcValue), original);
            if (Objects.equals(original, processed)) {
                return sqlLiteral ? valueProcessor.getJdbcSqlValueString(jdbcValue)
                        : valueProcessor.getJdbcValue(jdbcValue);
            }
            return sqlLiteral ? sqlLiteral(valueProcessor, processed)
                    : Objects.toString(processed.getValue(), null);
        };
    }

    private ExportCell exportCell(JDBCDataValue value) {
        Object rawValue = value.getObject();
        if (rawValue == null) {
            rawValue = value.getStringValue();
        }
        return new ExportCell(rawValue, value.getSqlType(), value.getType(), value.getPrecision(), value.getScale());
    }

    private ExportCellContext exportCellContext(SqlExecutionPlan plan, JDBCDataValue value) {
        SqlExecutionContext context = plan.getContext();
        String databaseName = jdbcMetadata(value, JdbcMetadataField.CATALOG);
        String schemaName = jdbcMetadata(value, JdbcMetadataField.SCHEMA);
        String tableName = jdbcMetadata(value, JdbcMetadataField.TABLE);
        String columnName = jdbcMetadata(value, JdbcMetadataField.COLUMN);
        return new ExportCellContext(context.getDataSourceId(), context.getDbType(),
                StringUtils.defaultIfBlank(databaseName, context.getDatabaseName()),
                StringUtils.defaultIfBlank(schemaName, context.getSchemaName()),
                StringUtils.defaultIfBlank(tableName, context.getTableName()), columnName, context.getExportType());
    }

    private String jdbcMetadata(JDBCDataValue value, JdbcMetadataField field) {
        try {
            return switch (field) {
                case CATALOG -> value.getMetaData().getCatalogName(value.getColumnIndex());
                case SCHEMA -> value.getMetaData().getSchemaName(value.getColumnIndex());
                case TABLE -> value.getMetaData().getTableName(value.getColumnIndex());
                case COLUMN -> value.getMetaData().getColumnName(value.getColumnIndex());
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sqlLiteral(IValueProcessor valueProcessor, ExportCell cell) {
        DataType dataType = new DataType();
        dataType.setDataTypeName(cell.getTypeName());
        dataType.setPrecision(cell.getPrecision());
        dataType.setScale(cell.getScale());
        SQLDataValue sqlDataValue = new SQLDataValue();
        sqlDataValue.setDataType(dataType);
        sqlDataValue.setValue(SqlValueSerializer.toSqlLiteral(cell.getValue()));
        return valueProcessor.getSqlValueString(sqlDataValue);
    }

    private <T> List<T> selectByListIndex(List<T> values, List<Integer> includedIndexes) {
        List<T> selected = new ArrayList<>(includedIndexes.size());
        for (Integer index : includedIndexes) {
            if (index != null && index >= 0 && index < values.size()) {
                selected.add(values.get(index));
            }
        }
        return selected;
    }

    private String requireSelectTableName(String sql, DbType dbType) {
        SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, dbType);
        if (!(sqlStatement instanceof SQLSelectStatement)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        return SqlUtils.getTableName(sql, dbType);
    }

    private enum JdbcMetadataField {
        CATALOG,
        SCHEMA,
        TABLE,
        COLUMN
    }

    @Data
    private static class ExcelWrapper {
        private ExcelWriter excelWriter;
        private MultiSheetExcelWriter multiSheetExcelWriter;
    }
}
