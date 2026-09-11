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
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.service.db.IDbDmlExportService;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionStatementListener;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.excel.MultiSheetExcelWriter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.ParamBusinessException;
import ai.chat2db.community.tools.util.EasyCollectionUtils;
import ai.chat2db.community.tools.util.EasyEnumUtils;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
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
import com.alibaba.excel.write.metadata.WriteSheet;
import com.google.common.collect.Lists;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.SpreadsheetVersion;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (ExportTypeEnum.CSV == exportType) {
            exportCsv(plan, outputStream, param.getResultSetId(), statementListener, cancellationChecker,
                    rowListener, finalizationListener);
            return;
        }
        if (ExportTypeEnum.EXCEL == exportType) {
            exportExcel(plan, outputStream, param.getResultSetId(), statementListener, cancellationChecker,
                    rowListener, finalizationListener);
            return;
        }
        exportInsert(param, plan, outputStream, statementListener, cancellationChecker, rowListener,
                finalizationListener);
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

    private void exportCsv(SqlExecutionPlan plan, OutputStream outputStream, Integer resultSetId,
            ISqlExecutionStatementListener statementListener, Runnable cancellationChecker,
            LongConsumer exportedRowsListener, Runnable fileFinalizationListener) {
        ExcelWrapper excelWrapper = new ExcelWrapper();
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        try {
            ExcelWriterBuilder excelWriterBuilder = EasyExcel.write(outputStream)
                    .charset(StandardCharsets.UTF_8)
                    .excelType(ExcelTypeEnum.CSV);
            List<Integer> includedIndexes = new ArrayList<>();
            DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), plan.getSql(), headerList -> {
                includedIndexes.addAll(sqlExecutionPolicyManager.includedColumnIndexes(plan, headerList));
                excelWriterBuilder.head(EasyCollectionUtils.toList(select(headerList, includedIndexes),
                        header -> Lists.newArrayList(header.getName())));
                excelWrapper.setExcelWriter(excelWriterBuilder.build());
                excelWrapper.setWriteSheet(EasyExcel.writerSheet(0).build());
            }, dataList -> {
                excelWrapper.getExcelWriter().write(List.of(select(dataList, includedIndexes)),
                        excelWrapper.getWriteSheet());
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
                List<List<String>> head = EasyCollectionUtils.toList(select(headerList, includedIndexes),
                        header -> Lists.newArrayList(header.getName()));
                excelWrapper.setExcelWriter(excelWriterBuilder.build());
                excelWrapper.setMultiSheetExcelWriter(new MultiSheetExcelWriter(excelWrapper.getExcelWriter(), head,
                        SpreadsheetVersion.EXCEL2007, "Data"));
                excelWrapper.getMultiSheetExcelWriter().initialize();
            }, dataList -> {
                excelWrapper.getMultiSheetExcelWriter().writeRow(select(dataList, includedIndexes));
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

    private void exportInsert(DbDmlExportRequest param, SqlExecutionPlan plan, OutputStream outputStream,
            ISqlExecutionStatementListener statementListener, Runnable cancellationChecker,
            LongConsumer exportedRowsListener, Runnable fileFinalizationListener) throws IOException {
        DbType dbType = currentDruidDbType();
        String tableName = dbType == null
                ? StringUtils.join(Lists.newArrayList(param.getDatabaseName(), param.getSchemaName()), "_")
                : requireSelectTableName(plan.getSql(), dbType);
        try (PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            AtomicReference<String> databaseName =
                    new AtomicReference<>(Chat2DBContext.getConnectInfo().getDatabaseName());
            AtomicReference<String> schemaName =
                    new AtomicReference<>(Chat2DBContext.getConnectInfo().getSchemaName());
            AtomicReference<String> resultTableName = new AtomicReference<>(tableName);
            List<String> headerColumns = Lists.newArrayList();
            ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
            IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            List<Integer> includedIndexes = new ArrayList<>();
            DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), plan.getSql(), headerList -> {
                includedIndexes.addAll(sqlExecutionPolicyManager.includedColumnIndexes(plan, headerList));
                List<Header> includedHeaders = select(headerList, includedIndexes);
                if (includedHeaders.isEmpty()) {
                    throw new IllegalStateException("SQL export has no authorized columns");
                }
                includedHeaders.stream().map(Header::getDatabaseName).filter(StringUtils::isNotBlank)
                        .findFirst().ifPresent(databaseName::set);
                includedHeaders.stream().map(Header::getSchemaName).filter(StringUtils::isNotBlank)
                        .findFirst().ifPresent(schemaName::set);
                includedHeaders.stream().map(Header::getTableName).filter(StringUtils::isNotBlank)
                        .findFirst().ifPresent(resultTableName::set);
                includedHeaders.forEach(header -> headerColumns.add(header.getName()));
            }, dataList -> {
                String insertSql = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                        .databaseName(databaseName.get())
                        .schemaName(schemaName.get())
                        .tableName(resultTableName.get())
                        .columnList(headerColumns)
                        .valueList(select(dataList, includedIndexes))
                        .build());
                printWriter.println(insertSql + ";");
                exportedRowsListener.accept(1L);
            }, exportValueFormatter(plan, valueProcessor, true), false, param.getResultSetId(), statementListener,
                    cancellationChecker, plan.getMaxRows());
            fileFinalizationListener.run();
            printWriter.flush();
            if (printWriter.checkError()) {
                throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                        "Could not write SQL export");
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
        sqlDataValue.setValue(toSqlValue(cell.getValue()));
        return valueProcessor.getSqlValueString(sqlDataValue);
    }

    private String toSqlValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "0x" + HexFormat.of().withUpperCase().formatHex(bytes);
        }
        if (value instanceof char[] chars) {
            return new String(chars);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<String> values = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                values.add(toSqlValue(Array.get(value, index)));
            }
            return values.toString();
        }
        if (value instanceof Collection<?> values) {
            return values.stream().map(this::toSqlValue).toList().toString();
        }
        if (value instanceof Map<?, ?> values) {
            Map<String, String> serialized = new LinkedHashMap<>(values.size());
            values.forEach((key, mapValue) -> serialized.put(toSqlValue(key), toSqlValue(mapValue)));
            return serialized.toString();
        }
        return String.valueOf(value);
    }

    private <T> List<T> select(List<T> values, List<Integer> includedIndexes) {
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
        private WriteSheet writeSheet;
        private MultiSheetExcelWriter multiSheetExcelWriter;
    }
}
