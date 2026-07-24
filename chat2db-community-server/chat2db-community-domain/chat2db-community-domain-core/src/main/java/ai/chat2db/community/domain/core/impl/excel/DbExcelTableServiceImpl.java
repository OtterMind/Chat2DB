package ai.chat2db.community.domain.core.impl.excel;

import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.domain.api.model.excel.ExcelCheckResponse;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.request.sql.DbSqlExecuteWithConnectionRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.domain.api.service.db.IDbExcelTableService;
import ai.chat2db.community.domain.api.service.db.IDbSqlService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.map.MapUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelReader;
import com.alibaba.excel.enums.ReadDefaultReturnEnum;
import com.alibaba.excel.read.metadata.ReadSheet;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DbExcelTableServiceImpl implements IDbExcelTableService {

    private static final String FILE_PATH = ConfigUtils.getBasePath() + File.separator + "excel" + File.separator + "EXCEL_DATA_%s";

    private static final String EXCEL_DATA_FILE_PATH = ConfigUtils.getBasePath() + File.separator + "excel" + File.separator + "%s" + File.separator + "EXCEL_DATA_FILE_%s";

    private final IDbSqlService sqlService;
    private final IDbConnectionContextService connectionContextService;
    private final IWorkspaceStorageFacade workspaceStorageFacade;

    public DbExcelTableServiceImpl(IDbSqlService sqlService, IDbConnectionContextService connectionContextService,
            IWorkspaceStorageFacade workspaceStorageFacade) {
        this.sqlService = sqlService;
        this.connectionContextService = connectionContextService;
        this.workspaceStorageFacade = workspaceStorageFacade;
    }

    @Override
    public ExcelCheckResponse check(String filePath) {
        ExcelCheckResponse result = new ExcelCheckResponse();
        try {
            result.setSheetList(new ArrayList<>());
            analyzeExcelMetadata(filePath, result);
            return result;
        } catch (Exception e) {
            log.error("check error", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void init(String filePath, Long id, ExcelCheckResponse config) {
        try (Connection connection = createExcelConnection(id, true)) {
            analyzeExcelWithRole(filePath, config, connection);
            buildCreateTable(config.getSheetList());
        } catch (Exception e) {
            log.error("check error", e);
            throw new RuntimeException(e);
        }

    }

    @Override
    public Map<Integer, Object> dateTime2Str(Map<Integer, Object> data) {
        if (MapUtil.isEmpty(data)) {
            return data;
        }
        return data.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            Object value = entry.getValue();
            if (value == null) {
                return "";
            }
            if (value instanceof LocalDateTime) {
                return DateUtil.format((LocalDateTime) value, "yyyy-MM-dd HH:mm:ss");
            }
            return value;
        }));
    }


    @Override
    public ExecuteResponse query(String sql, Long id) {
        try (Connection conn = createExcelConnection(id, false)) {
            DbSqlExecuteWithConnectionRequest sqlExecuteWithConnectionRequest = new DbSqlExecuteWithConnectionRequest();
            sqlExecuteWithConnectionRequest.setSql(sql);
            sqlExecuteWithConnectionRequest.setConnection(conn);
            sqlExecuteWithConnectionRequest.setOffset(false);
            sqlExecuteWithConnectionRequest.setPageNo(0);
            sqlExecuteWithConnectionRequest.setPageSize(1000);
            return sqlService.executeWithConnection(sqlExecuteWithConnectionRequest);
        } catch (SQLException e) { // impl-contract: fallback - Excel query failure is returned as failed ExecuteResponse.
            log.error("queryExcel error", e);
            ExecuteResponse result = new ExecuteResponse();
            result.setSuccess(false);
            result.setMessage("query excel datasource failed: " + e.getMessage());
            return result;
        }
    }

    @Override
    public Long createExcelDataSource(String originalFilename, String filePath) {
        String id = StringUtils.removeStart(FileUtil.getName(filePath), "EXCEL_FILE_");
        String dataFilePath = String.format(EXCEL_DATA_FILE_PATH, id, id);
        WorkspaceDataSource dataSource = new WorkspaceDataSource();
        dataSource.setAlias(originalFilename);
        dataSource.setType("H2");
        String jdbcUrl = "jdbc:h2:" + dataFilePath;
        dataSource.setUrl(jdbcUrl);
        dataSource.setStorageType("CLOUD");
        dataSource.setEnvironmentId(1L);
        dataSource.setDriverConfig(connectionContextService.getDefaultDriverConfig("H2"));
        return workspaceStorageFacade.createDataSource(dataSource);
    }

    private void analyzeExcelMetadata(String filePath, ExcelCheckResponse result) {
        ExcelReader excelReader = null;
        try {
            excelReader = EasyExcel.read(filePath).readDefaultReturn(ReadDefaultReturnEnum.ACTUAL_DATA).headRowNumber(0)
                    .registerReadListener(new ReadMetaListener(result, filePath, this)).build();
            List<ReadSheet> sheets = excelReader.excelExecutor().sheetList();
            excelReader.read(sheets);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (excelReader != null) {
                excelReader.close();
            }
        }
    }


    private Connection createExcelConnection(Long id, boolean create) {
        String filePath = String.format(FILE_PATH, id);
        if (!create && FileUtil.isEmpty(new File(filePath + ".mv.db"))) {
            throw new RuntimeException("file not exist");
        }
        String jdbcUrl = "jdbc:h2:" + filePath;
        Connection connection = null;
        try {
            Class.forName("org.h2.Driver");
            connection = DriverManager.getConnection(jdbcUrl, null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return connection;
    }

    private void analyzeExcelWithRole(String filePath, ExcelCheckResponse result, Connection connection) {
        ExcelReader excelReader = null;
        try {
            Map<Integer, ExcelCheckResponse.Sheet> sheetMap = result.getSheetList().stream().collect(Collectors.toMap(ExcelCheckResponse.Sheet::getSheetNo, s -> s));
            excelReader = EasyExcel.read(filePath).readDefaultReturn(ReadDefaultReturnEnum.ACTUAL_DATA)
                    .registerReadListener(new ReadHeaderListener(result, filePath, connection, this)).build();
            List<ReadSheet> sheets = excelReader.excelExecutor().sheetList();
            List<ReadSheet> readSheets = new ArrayList<>();
            for (ReadSheet sheet : sheets) {
                ExcelCheckResponse.Sheet iSheet = sheetMap.get(sheet.getSheetNo());
                if(iSheet!=null) {
                    iSheet.setHeaderList(new ArrayList<>());
                    if (iSheet != null && !iSheet.isDel()) {
                        sheet.setHeadRowNumber(iSheet.getHeaderEndRowNum());
                        readSheets.add(sheet);
                    }
                }
            }
            excelReader.read(readSheets);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (excelReader != null) {
                excelReader.close();
            }
        }
    }

    private void buildCreateTable(List<ExcelCheckResponse.Sheet> sheetList) {
        for (ExcelCheckResponse.Sheet sheet : sheetList) {
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE ").append(quoteIdentifier(sheet.getTableName())).append(" (").append("\n");
            for (ExcelCheckResponse.Header header : sheet.getHeaderList()) {
                sb.append(quoteIdentifier(header.getHeaderName())).append(" ");
                if (StringUtils.isNotBlank(header.getDataType())) {
                    sb.append(header.getDataType());
                } else {
                    sb.append("varchar(1024)");
                }
                sb.append(" NULL ");
                if (StringUtils.isNotBlank(header.getComment())) {
                    sb.append(" COMMENT '").append(header.getComment().replace("'", "''")).append("',");
                } else {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb = new StringBuilder(sb.substring(0, sb.length() - 2));
            sb.append("\n );");
            sheet.setDdl(sb.toString());
        }
    }

    // H2 double-quoted identifier; escape embedded double quotes by doubling.
    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

}
