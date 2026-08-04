package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.enums.ExportSizeEnum;
import ai.chat2db.community.domain.api.enums.ExportTypeEnum;
import ai.chat2db.community.domain.api.model.db.DbDmlExportPlan;
import ai.chat2db.community.domain.api.model.request.db.DbDmlExportRequest;
import ai.chat2db.community.domain.api.service.db.IDbDmlExportService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.ParamBusinessException;
import ai.chat2db.community.tools.util.EasyCollectionUtils;
import ai.chat2db.community.tools.util.EasyEnumUtils;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.SqlUtils;
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
import cn.hutool.core.date.DatePattern;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DbDmlExportServiceImpl implements IDbDmlExportService {

    @Override
    public String resolveTableName(String sql, String databaseName, String schemaName) {
        DbType dbType = currentDruidDbType();
        if (dbType == null) {
            return StringUtils.join(Lists.newArrayList(databaseName, schemaName), "_");
        }
        try {
            return SqlUtils.getTableName(sql, dbType);
        } catch (Exception ignored) { // impl-contract: fallback - export file naming falls back to database/schema when SQL parsing fails.
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
    public void export(DbDmlExportRequest param, OutputStream outputStream) throws IOException {
        ExportTypeEnum exportType = ExportTypeEnum.from(param.getExportType());
        if (ExportTypeEnum.CSV == exportType) {
            requireSelectSql(param.getSql());
            exportCsv(param.getSql(), outputStream, param.getResultSetId());
            return;
        }
        if (ExportTypeEnum.EXCEL == exportType) {
            requireSelectSql(param.getSql());
            exportExcel(param.getSql(), outputStream, param.getResultSetId());
            return;
        }
        exportInsert(param, outputStream);
    }

    private void requireSelectSql(String sql) {
        DbType dbType = currentDruidDbType();
        if (dbType == null) {
            return;
        }
        SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, dbType);
        if (!(sqlStatement instanceof SQLSelectStatement)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
    }

    private DbType currentDruidDbType() {
        return JdbcUtils.parse2DruidDbType(Chat2DBContext.getConnectInfo().getDbType());
    }

    private String resolveSql(DbDmlExportRequest param) {
        ExportSizeEnum exportSize = EasyEnumUtils.getEnum(ExportSizeEnum.class, param.getExportSize());
        String sql = exportSize == ExportSizeEnum.CURRENT_PAGE && StringUtils.isNotBlank(param.getSql())
                ? param.getSql()
                : param.getOriginalSql();
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

    private void exportCsv(String sql, OutputStream outputStream, Integer resultSetId) {
        ExcelWrapper excelWrapper = new ExcelWrapper();
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        try {
            ExcelWriterBuilder excelWriterBuilder = EasyExcel.write(outputStream)
                    .charset(StandardCharsets.UTF_8)
                    .excelType(ExcelTypeEnum.CSV);
            excelWrapper.setExcelWriterBuilder(excelWriterBuilder);
            DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), sql, headerList -> {
                excelWriterBuilder.head(
                        EasyCollectionUtils.toList(headerList, header -> Lists.newArrayList(header.getName())));
                excelWrapper.setExcelWriter(excelWriterBuilder.build());
                excelWrapper.setWriteSheet(EasyExcel.writerSheet(0).build());
            }, dataList -> {
                List<List<String>> writeDataList = Lists.newArrayList();
                writeDataList.add(dataList);
                excelWrapper.getExcelWriter().write(writeDataList, excelWrapper.getWriteSheet());
            }, valueProcessor::getJdbcValue, false, resultSetId);
        } finally {
            if (excelWrapper.getExcelWriter() != null) {
                excelWrapper.getExcelWriter().finish();
            }
        }
    }

    private void exportExcel(String sql, OutputStream outputStream, Integer resultSetId) {
        ExcelWrapper excelWrapper = new ExcelWrapper();
        IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
        try {
            ExcelWriterBuilder excelWriterBuilder = EasyExcel.write(outputStream)
                    .charset(StandardCharsets.UTF_8)
                    .excelType(ExcelTypeEnum.XLSX);
            excelWrapper.setExcelWriterBuilder(excelWriterBuilder);
            DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), sql, headerList -> {
                excelWriterBuilder.head(
                        EasyCollectionUtils.toList(headerList, header -> Lists.newArrayList(header.getName())));
                excelWrapper.setExcelWriter(excelWriterBuilder.build());
                excelWrapper.setWriteSheet(EasyExcel.writerSheet(0).build());
            }, dataList -> {
                List<List<String>> writeDataList = Lists.newArrayList();
                writeDataList.add(dataList);
                excelWrapper.getExcelWriter().write(writeDataList, excelWrapper.getWriteSheet());
            }, valueProcessor::getJdbcValue, false, resultSetId);
        } finally {
            if (excelWrapper.getExcelWriter() != null) {
                excelWrapper.getExcelWriter().finish();
            }
        }
    }

    private void exportInsert(DbDmlExportRequest param, OutputStream outputStream) throws IOException {
        DbType dbType = currentDruidDbType();
        String tableName = dbType == null
                ? StringUtils.join(Lists.newArrayList(param.getDatabaseName(), param.getSchemaName()), "_")
                : requireSelectTableName(param.getSql(), dbType);
        try (PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            String databaseName = Chat2DBContext.getConnectInfo().getDatabaseName();
            String schemaName = Chat2DBContext.getConnectInfo().getSchemaName();
            List<String> headerColumns = Lists.newArrayList();
            ISqlBuilder sqlBuilder = Chat2DBContext.getSqlBuilder();
            IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            DefaultSQLExecutor.getInstance().execute(Chat2DBContext.getConnection(), param.getSql(),
                    headerList -> headerList.forEach(header -> headerColumns.add(header.getName())),
                    dataList -> {
                        String insertSql = sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                                .databaseName(databaseName)
                                .schemaName(schemaName)
                                .tableName(tableName)
                                .columnList(headerColumns)
                                .valueList(dataList)
                                .build());
                        printWriter.println(insertSql + ";");
                    }, valueProcessor::getJdbcSqlValueString, false, param.getResultSetId());
        }
    }

    private String requireSelectTableName(String sql, DbType dbType) {
        if (dbType == null) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        SQLStatement sqlStatement = SQLUtils.parseSingleStatement(sql, dbType);
        if (!(sqlStatement instanceof SQLSelectStatement)) {
            throw new BusinessException("dataSource.sqlAnalysisError");
        }
        return SqlUtils.getTableName(sql, dbType);
    }

    @Data
    private static class ExcelWrapper {
        private ExcelWriterBuilder excelWriterBuilder;
        private ExcelWriter excelWriter;
        private WriteSheet writeSheet;
    }
}
