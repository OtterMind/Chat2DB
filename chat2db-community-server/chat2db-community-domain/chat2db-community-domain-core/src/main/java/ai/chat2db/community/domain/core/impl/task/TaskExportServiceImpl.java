package ai.chat2db.community.domain.core.impl.task;

import ai.chat2db.community.domain.api.enums.ExportScopeTypeEnum;
import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.request.task.TaskSqlFileExportRequest;
import ai.chat2db.community.domain.api.service.task.ITaskExportService;
import ai.chat2db.spi.model.request.TablesRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

@Slf4j
@Service
public class TaskExportServiceImpl implements ITaskExportService {

    @Override
    public void exportSqlFile(TaskSqlFileExportRequest param, AsyncContext asyncContext) {
        try {
            ExportScopeTypeEnum scope = ExportScopeTypeEnum.from(param.getScope());
            if (scope == null) {
                return;
            }
            switch (scope) {
                case ALL:
                case SCHEMA:
                    exportStructure(param, asyncContext);
                    break;
                case TABLE:
                    exportTableData(param, asyncContext);
                    break;
                default:
                    break;
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) { // impl-contract: fallback - async export reports failure through AsyncContext.error.
            log.error("exportSqlFile error", e);
            asyncContext.error(e.getMessage());
        }
    }

    private void exportStructure(TaskSqlFileExportRequest param, AsyncContext asyncContext) throws Exception {
            if (CollectionUtils.isEmpty(param.getTableNames())) {
                Chat2DBContext.getDbManager()
                    .exportDatabase(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(),
                            asyncContext);
                return;
            }
            for (String tableName : param.getTableNames()) {
                asyncContext.checkCancelled();
                Chat2DBContext.getDbManager()
                    .exportTable(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(),
                            tableName, asyncContext);
            }
    }

    private void exportTableData(TaskSqlFileExportRequest param, AsyncContext asyncContext) throws Exception {
        List<String> tables = param.getTableNames();
        if (CollectionUtils.isEmpty(tables)) {
            List<Table> tableList = Chat2DBContext.getDbMetaData()
                    .tables(Chat2DBContext.getConnection(),
                            new TablesRequest(param.getDatabaseName(), param.getSchemaName(), null));
            if (CollectionUtils.isNotEmpty(tableList)) {
                tables = tableList.stream().map(Table::getName).toList();
            } else {
                tables = new ArrayList<>();
            }
        }
        for (String table : tables) {
            asyncContext.checkCancelled();
            Chat2DBContext.getDbManager()
                    .exportTableData(Chat2DBContext.getConnection(), param.getDatabaseName(), param.getSchemaName(),
                            table, asyncContext);
        }
    }
}
