package ai.chat2db.community.domain.core.impl.task.export;

import ai.chat2db.community.domain.api.model.task.ExportAsyncContext;
import ai.chat2db.spi.sql.Chat2DBContext;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ZipUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CancellationException;


@Slf4j
public abstract class BaseExporter implements IExportStrategy {

    protected String contentType;

    protected String suffix;
    public static int BATCH_SIZE = 1000;

    @Override
    public void run(ExportAsyncContext asyncContext) {
        asyncContext.checkCancelled();
        List<String> tableNames = asyncContext.getTableNames();
        if (CollectionUtils.isEmpty(tableNames)) {
            throw new IllegalArgumentException("tableNames should not be null or empty");
        }
        try {
            if (tableNames.size() == 1) {
                asyncContext.setProgress(20);
                single(asyncContext);
            } else {
                multi(asyncContext);
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            asyncContext.error("export data error, " + e.getMessage());
            log.error("export data error", e);
        }
    }

    private void single(ExportAsyncContext asyncContext) throws IOException, SQLException {
        asyncContext.info(String.format("Exporting table %s", asyncContext.getTableNames().get(0)));
        singleExport(asyncContext, asyncContext.getTableNames().get(0), asyncContext.getWriteFile());
    }

    private void multi(ExportAsyncContext asyncContext) throws IOException, SQLException {
        String path = asyncContext.getWriteFile().getParent();
        FileUtil.mkdir(path);
        int n = asyncContext.getTableNames().size();
        String[] paths = new String[n];
        InputStream[] inputStreams = new InputStream[n];
        for (int i = 0; i < n; i++) {
            asyncContext.checkCancelled();
            String tableName = asyncContext.getTableNames().get(i);
            if (StringUtils.isEmpty(tableName)) {
                throw new IllegalArgumentException("tableName should not be null or empty");
            }
            File file = new File(path + File.separator + tableName + suffix);
            asyncContext.info(String.format("Exporting table %s", tableName));
            singleExport(asyncContext, tableName, file);
            paths[i] = tableName + suffix;
            inputStreams[i] = FileUtil.getInputStream(file);
        }
        asyncContext.checkCancelled();
        ZipUtil.zip(asyncContext.getWriteFile(), paths, inputStreams);
    }
    protected String getQuerySql(String tableName) {
        String databaseName = Chat2DBContext.getConnectInfo().getDatabaseName();
        String schemaName = Chat2DBContext.getConnectInfo().getSchemaName();
        return Chat2DBContext.getSqlBuilder().dql().buildSelectTable(databaseName, schemaName, tableName);
    }

    protected abstract void singleExport(ExportAsyncContext asyncContext, String tableName, File file) throws IOException, SQLException;

}
