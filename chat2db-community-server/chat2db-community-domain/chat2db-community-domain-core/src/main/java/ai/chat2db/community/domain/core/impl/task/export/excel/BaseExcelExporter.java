package ai.chat2db.community.domain.core.impl.task.export.excel;

import ai.chat2db.community.domain.core.impl.task.export.BaseExporter;
import ai.chat2db.community.domain.core.impl.task.export.ExportProgressLogger;
import ai.chat2db.community.domain.api.model.task.ExportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
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
        String querySql = getQuerySql(spec, tableName);
        Connection connection = Chat2DBContext.getConnection();
        ExportProgressLogger progressLogger = new ExportProgressLogger(context, excelType.name(), tableName);
        progressLogger.queryStarted("Reading table data from " + tableName);
        DefaultSQLExecutor.getInstance().execute(connection, querySql, BATCH_SIZE, resultSet ->
                writeExcelData(resultSet, excelType, file, tableName, spec, context, progressLogger),
                context, context::checkCancelled);
    }

    public static int BATCH_SIZE = 500;

    private void writeExcelData(ResultSet resultSet, ExcelTypeEnum excelType, File file, String sheetName,
            ExportTaskSpec spec, TaskExecutionContext context, ExportProgressLogger progressLogger) {
        try (ExcelWriter excelWriter = EasyExcel.write(file).excelType(excelType).build()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            IValueProcessor valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            List<List<String>> head = Collections.emptyList();
            if (Boolean.TRUE.equals(spec.getContainsHeader())) {
                List<String> header = ResultSetUtils.getRsHeader(resultSet);
                head = header.stream().map(Collections::singletonList).collect(Collectors.toList());
            }
            MultiSheetExcelWriter multiSheetWriter = null;
            WriteSheet writeSheet = null;
            if (excelType == ExcelTypeEnum.XLSX) {
                multiSheetWriter = new MultiSheetExcelWriter(excelWriter, head, SpreadsheetVersion.EXCEL2007,
                        sheetName);
                multiSheetWriter.initialize();
            } else {
                writeSheet = EasyExcel.writerSheet(sheetName).build();
                if (!head.isEmpty()) {
                    writeSheet.setHead(head);
                }
            }
            boolean hasNext = resultSet.next();
            while (hasNext) {
                context.checkCancelled();
                List<Object> rowDataList = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    JDBCDataValue jdbcDataValue = new JDBCDataValue(resultSet, metaData, i, false);
                    rowDataList.add(valueProcessor.getJdbcValue(jdbcDataValue));
                }
                if (multiSheetWriter == null) {
                    excelWriter.write(Collections.singletonList(rowDataList), writeSheet);
                } else {
                    multiSheetWriter.writeRow(rowDataList);
                }
                progressLogger.recordExportedRow();
                hasNext = resultSet.next();
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
