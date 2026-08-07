package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.spi.DefaultSqlSyntaxHandler;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.task.ImportAsyncContext;
import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.community.domain.core.impl.task.imports.*;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


@Slf4j
public class SQLImporter extends BaseImporter implements IImportStrategy {

    @Override
    protected void doImportData(ImportAsyncContext context, List<TableColumn> columns) {
        context.checkCancelled();
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        String databaseType = connectInfo.getDbType();
        if (StringUtils.equalsAnyIgnoreCase(databaseType, DatabaseTypeEnum.MYSQL.name(), DatabaseTypeEnum.ORACLE.name(), DatabaseTypeEnum.OSCAR.name(), DatabaseTypeEnum.SQLSERVER.name(), DatabaseTypeEnum.POSTGRESQL.name())) {
            ConsoleTaskProgressListener consoleProgressListener = new ConsoleTaskProgressListener(context);
            // MYSQL-IMPORT-004: options-aware handler (commit mode / error policy / batch
            // size) when configured; the charset overload decodes the file with the
            // selected encoding.
            SqlFileOptionsHandler optionsHandler = new SqlFileOptionsHandler(context);
            java.nio.charset.Charset charset = java.nio.charset.Charset.forName(
                    StringUtils.defaultIfBlank(context.getEncoding(), "UTF-8"));
            int statementCount = DefaultSqlSyntaxHandler.parserSqlScript(context.getFile(), databaseType,
                    consoleProgressListener, optionsHandler, charset);
            context.checkCancelled();
            log.info(" parsed {} statements", statementCount);
        } else {

            StringBuilder sb = new StringBuilder();
            List<String> sqls = new ArrayList<>();
            DbType dbType = JdbcUtils.parse2DruidDbType(databaseType);
            long totalBytes = context.getFile().length();
            AtomicLong bytesRead = new AtomicLong();
            StringBuilder processStr = new StringBuilder("");
            long s1 = System.currentTimeMillis();
            AtomicInteger n = new AtomicInteger();
            FileUtil.readLines(context.getFile(), Charset.forName("UTF-8"), (LineHandler) line -> {
                context.checkCancelled();
                bytesRead.addAndGet(line.getBytes().length + System.lineSeparator().getBytes().length);
                setProgress(context, bytesRead.get(), totalBytes, processStr);
                sb.append(line + "\n");
                String s = line == null ? "" : line.trim();

                if (s.endsWith(";")) {
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
                        sb.append("\n");
                    }
                }
                if (sqls.size() >= 100) {
                    context.execute(sqls);
                    sqls.clear();
                }
            });
            String endStr = sb.toString();
            if (StringUtils.isNotBlank(endStr) && !endStr.trim().equals(";")) {
                sqls.add(EasyStringUtils.sqlEscape(endStr));
            }
            log.info("parse sql cost:{}", System.currentTimeMillis() - s1);
            context.execute(sqls);
        }
    }

    private void setProgress(ImportAsyncContext context, long i, long size, StringBuilder processStr) {
        long progress = i * 100 / size;
        Integer p = Integer.valueOf(progress + "");
        if (p >= 100) {
            p = 99;
        }
        if (!processStr.toString().equals(p.toString())) {
            processStr.setLength(0);
            processStr.append(p);
            context.setProgress(p);
            context.info(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss") + " all bytes:" + size + ",current bytes:" + i + ",progress:" + progress + "%");
        }

    }
}
