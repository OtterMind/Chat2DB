package ai.chat2db.community.domain.core.impl.task.export.excel;

import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportCellProcessorChain;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.community.domain.core.impl.db.extension.SqlExecutionPolicyManager;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.domain.api.model.task.extension.ExportCell;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.util.ResultSetUtils;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.metadata.WriteSheet;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.SpreadsheetVersion;

import java.io.File;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
public abstract class BaseExcelExporter extends BaseExporter {

    protected BaseExcelExporter(ExportCellProcessorChain exportCellProcessorChain,
            SqlExecutionPolicyManager sqlExecutionPolicyManager) {
        super(exportCellProcessorChain, sqlExecutionPolicyManager);
    }

    static {
        SpreadsheetVersion excel2007 = SpreadsheetVersion.EXCEL2007;
        SpreadsheetVersion excel97 = SpreadsheetVersion.EXCEL97;
        if (Integer.MAX_VALUE != excel2007.getMaxTextLength()) {
            Field field;
            try {
                field = excel2007.getClass().getDeclaredField("_maxTextLength");
                field.setAccessible(true);
                field.set(excel2007, Integer.MAX_VALUE);
            } catch (Exception e) {
                log.error("Error setting max text length", e);
            }
        }
        if (Integer.MAX_VALUE != excel97.getMaxTextLength()) {
            Field field;
            try {
                field = excel97.getClass().getDeclaredField("_maxTextLength");
                field.setAccessible(true);
                field.set(excel97, Integer.MAX_VALUE);
            } catch (Exception e) {
                log.error("Error setting max text length", e);
            }
        }
    }

    @Override
    protected void singleExport(ExportTaskSpec spec, TaskExecutionContext context, String tableName, File file) {
        ExcelTypeEnum excelType = getExcelType();
        SqlExecutionPlan executionPlan = getQueryPlan(spec, tableName);
        Connection connection = Chat2DBContext.getConnection();
        ExportProgressLogger progressLogger = new ExportProgressLogger(context, excelType.name(), tableName);
        progressLogger.queryStarted("Reading table data from " + tableName);
        DefaultSQLExecutor.getInstance().execute(connection, executionPlan.getSql(), BATCH_SIZE, resultSet ->
                writeExcelData(resultSet, excelType, file, tableName, spec, executionPlan, context, progressLogger),
                context, context::checkCancelled);
    }

    public static int BATCH_SIZE = 500;

    private void writeExcelData(ResultSet resultSet, ExcelTypeEnum excelType, File file, String sheetName,
            ExportTaskSpec spec, SqlExecutionPlan executionPlan, TaskExecutionContext context,
            ExportProgressLogger progressLogger) {
        try (ExcelWriter excelWriter = EasyExcel.write(file).excelType(excelType).build()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            List<Integer> includedColumnIndexes = includedColumnIndexes(metaData, executionPlan);
            IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            List<List<String>> head = Collections.emptyList();
            if (Boolean.TRUE.equals(spec.getContainsHeader())) {
                List<String> header = selectColumns(ResultSetUtils.getRsHeader(resultSet), includedColumnIndexes);
                head = header.stream().map(Collections::singletonList).collect(Collectors.toList());
            }
            MultiSheetExcelWriter multiSheetWriter = null;
            WriteSheet writeSheet = null;
            if (excelType == ExcelTypeEnum.XLSX || excelType == ExcelTypeEnum.XLS) {
                SpreadsheetVersion spreadsheetVersion = excelType == ExcelTypeEnum.XLS
                        ? SpreadsheetVersion.EXCEL97 : SpreadsheetVersion.EXCEL2007;
                multiSheetWriter = new MultiSheetExcelWriter(excelWriter, head, spreadsheetVersion, sheetName);
                multiSheetWriter.initialize();
            } else {
                writeSheet = EasyExcel.writerSheet(sheetName).build();
                if (!head.isEmpty()) {
                    writeSheet.setHead(head);
                }
            }
            int exportedRows = 0;
            boolean hasNext = nextRow(resultSet, executionPlan, exportedRows);
            while (hasNext) {
                context.checkCancelled();
                List<Object> rowDataList = new ArrayList<>(includedColumnIndexes.size());
                for (Integer columnIndex : includedColumnIndexes) {
                    JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, columnIndex, false);
                    if (hasExportCellProcessors()) {
                        ExportCell cell = processJdbcCell(spec, metaData, columnIndex, sheetName, jdbcDataValue);
                        rowDataList.add(cell.getValue());
                    } else {
                        rowDataList.add(valueProcessor.getJdbcValue(jdbcDataValue));
                    }
                }
                if (multiSheetWriter == null) {
                    excelWriter.write(Collections.singletonList(rowDataList), writeSheet);
                } else {
                    multiSheetWriter.writeRow(rowDataList);
                }
                progressLogger.recordExportedRow();
                exportedRows++;
                hasNext = nextRow(resultSet, executionPlan, exportedRows);
            }
            progressLogger.queryCompleted("Table data read completed");
            progressLogger.fileFinalizing();
        } catch (TaskCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error writing Excel data", e);
            throw new TaskExecutionException(TaskErrorCode.FILE_WRITE_FAILED.name(),
                    "Could not write Excel export", e);
        }
    }


    protected abstract ExcelTypeEnum getExcelType();
}
