package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.spi.DefaultSqlSyntaxHandler;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskEventCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskStage;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.community.domain.core.impl.task.imports.*;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.SqlUtils;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.LineHandler;
import com.alibaba.druid.DbType;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.Charset;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;


@Slf4j
public class SQLImporter implements IImportStrategy {

    @Override
    public void run(ImportTaskSpec spec, TaskExecutionContext context) {
        try {
            context.checkCancelled();
            File sourceFile = new File(spec.getSourceFile());
            ImportSqlExecutor sqlExecutor = new ImportSqlExecutor(context);
            ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
            String databaseType = connectInfo.getDbType();
            context.logInfo(TaskEventCode.FILE_READ_STARTED.name(), "Reading SQL import file");
            if (StringUtils.equalsAnyIgnoreCase(databaseType, DatabaseTypeEnum.MYSQL.name(),
                    DatabaseTypeEnum.ORACLE.name(), DatabaseTypeEnum.OSCAR.name(),
                    DatabaseTypeEnum.SQLSERVER.name(), DatabaseTypeEnum.POSTGRESQL.name())) {
                ConsoleTaskProgressListener consoleProgressListener =
                        new ConsoleTaskProgressListener(context, sourceFile);
                SyncSqlBatchHandler syncSqlBatchHandler = new SyncSqlBatchHandler(context, sqlExecutor);
                int statementCount = DefaultSqlSyntaxHandler.parserSqlScript(
                        sourceFile, databaseType, consoleProgressListener, syncSqlBatchHandler);
                context.checkCancelled();
                context.logInfo(TaskEventCode.FILE_READ_COMPLETED.name(), "SQL file parsed",
                        Map.of("statementCount", statementCount));
            } else {
                StringBuilder sb = new StringBuilder();
                List<String> sqls = new ArrayList<>();
                DbType dbType = JdbcUtils.parse2DruidDbType(databaseType);
                long totalBytes = sourceFile.length();
                AtomicLong bytesRead = new AtomicLong();
                StringBuilder processStr = new StringBuilder();
                long startedAt = System.currentTimeMillis();
                FileUtil.readLines(sourceFile, Charset.forName("UTF-8"), (LineHandler) line -> {
                    context.checkCancelled();
                    bytesRead.addAndGet(line.getBytes().length + System.lineSeparator().getBytes().length);
                    setProgress(context, bytesRead.get(), totalBytes, processStr);
                    sb.append(line).append('\n');
                    String trimmed = line == null ? "" : line.trim();

                    if (trimmed.endsWith(";")) {
                        List<String> list = SqlUtils.parse(sb.toString(), dbType, false);
                        if (CollectionUtils.isNotEmpty(list)) {
                            for (int i = 0; i < list.size() - 1; i++) {
                                String sql = list.get(i);
                                if (StringUtils.isNotBlank(sql) && !sql.trim().equals(";")) {
                                    sqls.add(EasyStringUtils.sqlEscape(sql));
                                }
                            }
                            sb.setLength(0);
                            String last = list.get(list.size() - 1);
                            sb.append(last);
                            if (!last.trim().endsWith(";")) {
                                sb.append(";");
                            }
                            sb.append('\n');
                        }
                    }
                    if (sqls.size() >= 100) {
                        sqlExecutor.executeBatch(sqls);
                        sqls.clear();
                    }
                });
                String endStr = sb.toString();
                if (StringUtils.isNotBlank(endStr) && !endStr.trim().equals(";")) {
                    sqls.add(EasyStringUtils.sqlEscape(endStr));
                }
                log.info("parse sql cost:{}", System.currentTimeMillis() - startedAt);
                sqlExecutor.executeBatch(sqls);
                context.logInfo(TaskEventCode.FILE_READ_COMPLETED.name(), "SQL file parsed");
            }
        } catch (TaskCancelledException | TaskExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not import SQL file", e);
            throw new TaskExecutionException(TaskErrorCode.IMPORT_FAILED.name(),
                    "Could not import SQL file", e);
        }
    }

    private void setProgress(TaskExecutionContext context, long i, long size, StringBuilder processStr) {
        long progress = size <= 0 ? 99 : i * 100 / size;
        Integer p = Integer.valueOf(progress + "");
        if (p >= 100) {
            p = 99;
        }
        if (!processStr.toString().equals(p.toString())) {
            processStr.setLength(0);
            processStr.append(p);
            context.reportProgress(p, TaskStage.IMPORTING.name(),
                    DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + " all bytes:" + size
                            + ",current bytes:" + i + ",progress:" + progress + "%");
        }

    }
}
