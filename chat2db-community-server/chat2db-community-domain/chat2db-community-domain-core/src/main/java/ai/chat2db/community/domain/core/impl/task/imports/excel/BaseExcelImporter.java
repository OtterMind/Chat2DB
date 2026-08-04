package ai.chat2db.community.domain.core.impl.task.imports.excel;

import ai.chat2db.community.domain.core.impl.task.imports.BaseImporter;
import ai.chat2db.community.domain.api.model.task.ImportAsyncContext;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.IValueProcessor;
import ai.chat2db.community.domain.api.model.metadata.DataType;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.SingleInsertSqlRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.util.ConverterUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.CancellationException;


@Slf4j
public abstract class BaseExcelImporter extends BaseImporter {
    @Override
    protected void doImportData(ImportAsyncContext context, List<TableColumn> columns) {
        context.checkCancelled();
        ExcelTypeEnum excelType = getExcelType();
        NoModelDataListener noModelDataListener = new NoModelDataListener(context, columns);
        EasyExcel.read(context.getFile(), noModelDataListener)
                .excelType(excelType)
                .sheet()
                .headRowNumber(1)
                .doRead();
        context.checkCancelled();

    }

    protected abstract ExcelTypeEnum getExcelType();


    public class NoModelDataListener extends AnalysisEventListener<Map<Integer, String>> {


        private ImportAsyncContext context;

        private List<TableColumn> columns;

        private Map<String, Integer> headMap;

        private List<TableColumn> tableColumns;

        private List<String> tableColumnList;

        private List<String> sqlList;

        private static final int BATCH_SIZE = 1000;

        private IValueProcessor valueProcessor;

        private ConnectInfo connectInfo;

        private ISqlBuilder sqlBuilder;

        private Connection connection;

        public NoModelDataListener(ImportAsyncContext context, List<TableColumn> columns) {
            this.columns = columns;
            this.context = context;
            this.valueProcessor = Chat2DBContext.getDbMetaData().getValueProcessor();
            this.connectInfo = Chat2DBContext.getConnectInfo();
            this.sqlBuilder = Chat2DBContext.getSqlBuilder();
            this.connection = Chat2DBContext.getConnection();
        }


        @Override
        public void invokeHead(Map<Integer, ReadCellData<?>> headMap, AnalysisContext context) {
            this.context.checkCancelled();
            Map<Integer, String> map = ConverterUtils.convertToStringMap(headMap, context);
            this.headMap = invertMap(map);
            this.tableColumns = getTableColumns(columns, this.headMap);
        }

        private List<TableColumn> getTableColumns(List<TableColumn> columns, Map<String, Integer> headMap) {
            List<TableColumn> tableColumns = new ArrayList<>();
            this.tableColumnList = new ArrayList<>();
            for (TableColumn column : columns) {
                if (headMap.containsKey(column.getName().toUpperCase())) {
                    tableColumns.add(column);
                    this.tableColumnList.add(column.getName());
                }
            }
            return tableColumns;
        }

        private Map<String, Integer> invertMap(Map<Integer, String> map) {
            Map<String, Integer> out = new HashMap(map.size());
            Iterator it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, String> entry = (Map.Entry) it.next();
                if (entry.getValue() != null) {
                    out.put(entry.getValue().toUpperCase(), entry.getKey());
                }
            }
            return out;
        }


        @Override
        public void invoke(Map<Integer, String> data, AnalysisContext context) {
            this.context.checkCancelled();
            if (data == null || data.isEmpty()) {
                return;
            }
            List<String> values = getValueList(data);

            String sql = getInsertSql(values);

            if (StringUtils.isBlank(sql)) {
                return;
            }
            if (sqlList == null) {
                sqlList = new ArrayList<>();
            }
            sqlList.add(sql);
            if (sqlList.size() >= BATCH_SIZE) {
                executeBatchInsert();
            } else {

            }
        }

        private List<String> getValueList(Map<Integer, String> data) {
            List<String> values = new ArrayList<>();
            for (TableColumn column : tableColumns) {
                Integer index = headMap.get(column.getName().toUpperCase());
                if (index == null) {
                    values.add(null);
                    continue;
                }
                String value = data.get(index);
                if (value == null) {
                    values.add(null);
                } else {
                    String stringValue = valueProcessor.getSqlValueString(getSQLDataValue(value, column));
                    values.add(stringValue);
                }
            }
            return values;
        }

        private String getInsertSql(List<String> values) {
            return sqlBuilder.dml().buildInsert(SingleInsertSqlRequest.builder()
                    .databaseName(connectInfo.getDatabaseName())
                    .schemaName(connectInfo.getSchemaName())
                    .tableName(this.context.getTableName())
                    .columnList(this.tableColumnList)
                    .valueList(values)
                    .build());
        }

        private SQLDataValue getSQLDataValue(String value, TableColumn column) {
            DataType dataType = new DataType();
            dataType.setDataTypeName(column.getColumnType());
            dataType.setScale(column.getDecimalDigits());
            dataType.setPrecision(column.getColumnSize());
            SQLDataValue sqlDataValue = new SQLDataValue();
            sqlDataValue.setDataType(dataType);
            sqlDataValue.setValue(value);
            return sqlDataValue;
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            this.context.checkCancelled();
            executeBatchInsert();
        }

        private void executeBatchInsert() {
            try {
                context.checkCancelled();
                if (CollectionUtils.isNotEmpty(sqlList)) {
                    context.info(String.format("Executing batch insert: %s", sqlList.size()));
                    DefaultSQLExecutor.getInstance().executeBatchInsert(
                            connection, sqlList, context, context::checkCancelled);
                    context.checkCancelled();
                }
            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                log.error("Error executing batch insert", e);
                context.error(String.format("Error executing batch insert: %s,%s", e.getMessage(), sqlList.toString()));
            } finally {
                sqlList = new ArrayList<>();
            }
        }
    }

}
