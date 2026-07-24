package ai.chat2db.community.domain.core.impl.excel;

import ai.chat2db.community.domain.api.model.excel.ExcelCheckResponse;
import ai.chat2db.community.domain.api.service.db.IDbExcelTableService;
import ai.chat2db.community.tools.util.EasyStringUtils;
import cn.hutool.core.io.FileUtil;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.metadata.data.ReadCellData;
import com.alibaba.excel.read.listener.ReadListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class ReadHeaderListener implements ReadListener<Map<Integer, Object>> {
    private String filePath;

    private ExcelCheckResponse result;

    private Map<Integer, ExcelCheckResponse.Sheet> sheetMap;

    private IDbExcelTableService excelTableService;


    private ExcelCheckResponse.Sheet currentSheet;


    private Connection connection;


    private List<String> sqlList = new ArrayList<>();


    public ReadHeaderListener(ExcelCheckResponse result, String filePath, Connection connection, IDbExcelTableService excelTableService) {
        this.result = result;
        this.filePath = filePath;
        this.connection = connection;
        this.excelTableService = excelTableService;
        sheetMap = result.getSheetList().stream().collect(Collectors.toMap(ExcelCheckResponse.Sheet::getSheetNo, s -> s));
    }

    @Override
    public void invokeHead(Map<Integer, ReadCellData<?>> headMap, AnalysisContext context) {
        Integer sheetNo = context.readSheetHolder().getSheetNo();
        setCurrentSheet(sheetNo);
        if ("horizontal".equalsIgnoreCase(currentSheet.getTableType())) {
            for (int i = 0; i < headMap.size(); i++) {
                ExcelCheckResponse.Header header = new ExcelCheckResponse.Header();
                if (currentSheet.getHeaderList().size() > i) {
                    header = currentSheet.getHeaderList().get(i);
                }
                String head = headMap.get(i) == null ? null : headMap.get(i).getStringValue();
                if (head == null) {
                    if (StringUtils.isBlank(header.getHeaderName())) {
                        head = "COLUMN_" + i;
                    }
                } else {
                    head = head.trim().toUpperCase();
                }
                header.setHeaderName(head);
                header.setColNum(i);
                currentSheet.getHeaderList().add(header);
            }
        }
    }

    private void setCurrentSheet(Integer sheetNo) {
        if (FileUtil.getSuffix(filePath).equalsIgnoreCase("csv")) {
            currentSheet = sheetMap.get(0);
        } else {
            currentSheet = sheetMap.get(sheetNo);
        }
        if (currentSheet.getDataList() == null) {
            currentSheet.setDataList(new ArrayList<>());
        }
    }

    private String buildCreateTable(ExcelCheckResponse.Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append("\"").append(sheet.getTableName()).append("\"").append(" (").append("\n");
        for (ExcelCheckResponse.Header header : sheet.getHeaderList()) {
            sb.append("\"").append(header.getHeaderName()).append("\"").append(" ");
            if (StringUtils.isNotBlank(header.getDataType())) {
                sb.append(header.getDataType());
            } else {
                sb.append("varchar(1024)");
            }
            sb.append(" NULL ");
            if (StringUtils.isNotBlank(header.getComment())) {
                sb.append(" COMMENT '").append(header.getComment()).append("',");
            } else {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb = new StringBuilder(sb.substring(0, sb.length() - 2));
        sb.append("\n );");
        return sb.toString();
    }

    private void createTable(ExcelCheckResponse.Sheet sheet) {
        String sql = buildCreateTable(sheet);
        if (StringUtils.isEmpty(sql)) {
            return;
        }
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            statement = this.connection.createStatement();
            statement.execute(sql);
        } catch (SQLException e) {
            log.info("SQLException", e);
        } finally {
            try {
                if (resultSet != null) {
                    resultSet.close();
                }
                if (statement != null) {
                    statement.close();
                }
            } catch (SQLException e) {
                log.info("SQLException", e);
            }
        }
    }

    @Override
    public void invoke(Map<Integer, Object> data, AnalysisContext context) {
        if (currentSheet == null) {
            setCurrentSheet(context.readSheetHolder().getSheetNo());
        }
        data = excelTableService.dateTime2Str(data);
        if ("vertical".equalsIgnoreCase(currentSheet.getTableType())) {
            List<Object> val = data.values().stream().toList();
            currentSheet.getDataList().add(val);
        } else {
            buildHeaderDataType(data);
            buildSql(data);
        }
    }

    private void buildSql(Map<Integer, Object> data) {
        List<String> valueList = new ArrayList<>();
        boolean allNull = true;
        for (int i = 0; i < currentSheet.getHeaderNameList().size(); i++) {
            Object v = data.get(i);
            if (v == null || StringUtils.isBlank(v.toString()) || v.toString().trim().equals("-")) {
                valueList.add("null");
            } else {
                allNull = false;
                valueList.add(EasyStringUtils.escapeAndQuoteString(v.toString()));
            }
        }
        if (allNull) {
            return;
        }
        String tableName = "\"" + currentSheet.getTableName() + "\"";
        String sql = buildInsert(tableName, currentSheet.getHeaderNameList(), valueList);
        if (sqlList.size() < 500) {
            sqlList.add(sql);
        } else {
            insertData(sqlList);
            sqlList.clear();
            sqlList.add(sql);
        }
    }

    private void insertData(List<String> sqlList) {
        if (CollectionUtils.isEmpty(sqlList)) {
            return;
        }
        createTable(currentSheet);
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.createStatement();
            for (String sql : sqlList) {
                statement.addBatch(sql);
            }
            statement.executeBatch();
            statement.clearBatch();
        } catch (SQLException e) {
            log.info("SQLException", e);
        } finally {
            try {
                if (resultSet != null) {
                    resultSet.close();
                }
                if (statement != null) {
                    statement.close();
                }
            } catch (SQLException e) {
                log.info("SQLException", e);
            }
        }
    }

    private String buildInsert(String tableName, List<String> columnList, List<String> valueList) {
        return "INSERT INTO " + tableName + " (" + String.join(",", columnList) + ") VALUES ("
                + String.join(",", valueList) + ")";
    }

    private void buildHeaderDataType(Map<Integer, Object> data) {
        List<ExcelCheckResponse.Header> headerList = currentSheet.getHeaderList();
        for (int i = 0; i < data.size(); i++) {
            if (headerList.size() <= i) {
                ExcelCheckResponse.Header header = new ExcelCheckResponse.Header();
                header.setHeaderName("COLUMN_" + i);
                header.setColNum(i);
                headerList.add(header);
                currentSheet.setColNum(data.size());
                currentSheet.setHeaderList(headerList);
            }
            ExcelCheckResponse.Header header = headerList.get(i);
            Object value = data.get(i);
            setHeaderDataType(header, value);
            if (StringUtils.isBlank(header.getComment()) && ObjectUtils.allNotNull(value)) {
                header.setComment(header.getHeaderName() + " , data example: " + value.toString());
            }
        }
    }

    private void setHeaderDataType(ExcelCheckResponse.Header header, Object value) {
        if (value == null || StringUtils.isBlank(value.toString())) {
            return;
        }
        if ("text".equals(header.getDataType())) {
            return;
        }
        if ("varchar(1024)".equals(header.getDataType())) {
            if (value.toString().length() > 1024) {
                header.setDataType("text");
            }
            return;
        }
        if (value.toString().length() > 1024) {
            header.setDataType("text");
            return;
        }
        if (value.toString().length() > 20) {
            header.setDataType("varchar(1024)");
            return;
        }
        if (value instanceof Number) {
            if ("double".equals(header.getDataType()) || value.toString().contains(".")) {
                header.setDataType("double");
            } else {
                header.setDataType("bigint");
            }
            return;
        }
        header.setDataType("varchar(1024)");
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        if ("vertical".equalsIgnoreCase(currentSheet.getTableType())) {
            List<List<Object>> datalList = currentSheet.getDataList();
            List<Map<Integer, Object>> colMaps = new ArrayList<>();
            if (CollectionUtils.isEmpty(datalList)) {
                return;
            }
            int n = 0;
            for (List<Object> objects : datalList) {
                n++;
                if (CollectionUtils.isEmpty(objects)) {
                    continue;
                }
                ExcelCheckResponse.Header header = new ExcelCheckResponse.Header();
                for (int i = currentSheet.getHeaderEndColNum() - 1; i < objects.size(); i++) {
                    Object value = objects.get(i);
                    if (i == currentSheet.getHeaderEndColNum() - 1) {
                        if (value != null && StringUtils.isNotBlank(value.toString())) {
                            header.setHeaderName(value.toString().toUpperCase());
                        } else {
                            header.setHeaderName("COLUMN_" + n);
                        }
                        header.setColNum(i);
                        currentSheet.addHeader(header);
                    } else {
                        setHeaderDataType(header, objects.get(i));
                        if (value != null && StringUtils.isBlank(header.getComment())) {
                            header.setComment(header.getHeaderName() + " , data example: " + value.toString());
                        }
                        Map<Integer, Object> val = null;
                        if (colMaps.size() <= i - currentSheet.getHeaderEndColNum()) {
                            val = new LinkedHashMap<>();
                            colMaps.add(val);
                        } else {
                            val = colMaps.get(i - currentSheet.getHeaderEndColNum());
                        }
                        val.put(n - 1, value);
                    }
                }
            }
            for (Map<Integer, Object> colMap : colMaps) {
                buildSql(colMap);
            }
            insertData(sqlList);

        } else {
            insertData(sqlList);
            sqlList.clear();
        }
    }
}
