package ai.chat2db.community.domain.api.model.task;

import ai.chat2db.community.domain.api.model.async.AsyncContext;
import ai.chat2db.community.domain.api.service.task.ITaskAsyncCall;
import ai.chat2db.community.domain.api.service.task.ITaskImportSqlExecutor;
import ai.chat2db.community.tools.model.Context;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;

import java.io.File;
import java.util.List;

@Setter
@Getter
public class ImportAsyncContext extends AsyncContext {

    private String importType;

    private String tableName;

    private File file;

    private String dataTimeFormat;

    private int batch = 0;

    private ITaskImportSqlExecutor sqlExecutor;

    public ImportAsyncContext(ITaskAsyncCall call, Context context, String importType, String tableName, File file) {
        super(call, context, null, false);
        this.importType = importType;
        this.tableName = tableName;
        this.file = file;
    }

    public int nextBatch() {
        return batch++;
    }

    public void execute(List<String> sqls) {
        checkCancelled();
        if (CollectionUtils.isEmpty(sqls) || sqlExecutor == null) {
            return;
        }
        String message = sqlExecutor.executeBatch(nextBatch(), sqls, this, this::checkCancelled);
        checkCancelled();
        if ("success".equals(message)) {
            info("batch execute success :" + batch);
        } else {
            error(message + " batch:" + batch);
        }
    }

    public void execute(String sql) {
        checkCancelled();
        if (sqlExecutor == null) {
            return;
        }
        String message = sqlExecutor.executeSql(nextBatch(), sql, this, this::checkCancelled);
        checkCancelled();
        if ("success".equals(message)) {
            info("batch execute success :" + batch);
        } else {
            error(message + " batch:" + batch);
        }
    }
}
