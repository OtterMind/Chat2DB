package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.request.datasource.DbConnectionDiffRequest;
import ai.chat2db.community.domain.api.service.db.IDbDiffService;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.tools.util.JdbcUrlUtils;
import ai.chat2db.community.domain.core.converter.ConnectionContextConverter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import ai.chat2db.spi.util.JdbcUtils;
import ai.chat2db.spi.util.SqlUtils;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.alibaba.druid.DbType;
import liquibase.database.Database;

import java.io.*;
import java.sql.Connection;
import java.util.List;

import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.DiffResult;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.resource.FileSystemResourceAccessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.xml.parsers.ParserConfigurationException;

@Slf4j
@Service
public class DbDiffServiceImpl implements IDbDiffService {

    private static final String DIFF_FILE = "diff.xml";

    @Autowired
    private IDbWorkspaceDataSourceService workspaceDataSourceService;

    @Autowired
    private ConnectionContextConverter connectionContextConverter;

    @Override
    public String diff(DbConnectionDiffRequest sourceDbConnectionDiffRequest,
            DbConnectionDiffRequest targetDbConnectionDiffRequest) {
        ConnectInfo source = buildConnectInfo(sourceDbConnectionDiffRequest);
        ConnectInfo target = buildConnectInfo(targetDbConnectionDiffRequest);
        Long id = IdUtil.getSnowflakeNextId();
        String filePath = ConfigUtils.getBasePath() + File.separator + "diff" +
                File.separator + id;
        FileUtil.mkdir(filePath);
        String databaseChangeLogTableName = "chat2db_database_change_" + id;
        String databaseChangeLogLockTableName = "chat2db_database_change_" + id + "_lock";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            Thread.currentThread().setContextClassLoader(DbDiffServiceImpl.class.getClassLoader());
        }
        try (
                FileSystemResourceAccessor resourceAccessor = new FileSystemResourceAccessor(filePath + File.separator);
                Connection sourceConn = ConnectionPool.createNewConnection(source);
                Connection targetConn = ConnectionPool.createNewConnection(target);
                Database sourceDatabase = initializeDatabase(sourceConn, source);
                Database targetDatabase = initializeDatabase(targetConn, target);
                StringWriter stringWriter = new StringWriter()
        ) {
            DiffResult diffResult = generateDiff(sourceDatabase, targetDatabase);

            String path = filePath + File.separator + DIFF_FILE;
            generateChangeLog(diffResult, path, target.getDbType());

            if (!FileUtil.exist(path)) {
                return "-- No differences. ";
            }
            configureLiquibaseTrackingTables(targetDatabase, databaseChangeLogTableName, databaseChangeLogLockTableName);
            String diffOutput = executeLiquibaseUpdate(targetDatabase, resourceAccessor, stringWriter);
            String filteredOutput = filter(diffOutput, target.getDbType(), databaseChangeLogTableName, databaseChangeLogLockTableName);

            if (StringUtils.isBlank(filteredOutput)) {
                log.error("diff error, filteredOutput is empty:" + source.getDatabaseName() + ":" + source.getSchemaName() + "," + target.getDatabaseName() + ":" + target.getSchemaName());
                log.error("diff xml:" + FileUtil.readUtf8String(filePath + File.separator + DIFF_FILE));
            }
            return filteredOutput;
        } catch (Exception e) {
            log.error("diff error", e);
            if (FileUtil.exist(filePath + File.separator + DIFF_FILE)) {
                log.error("diff xml:" + FileUtil.readUtf8String(filePath + File.separator + DIFF_FILE));
            }
            throw new BusinessException("diff_error", new Object[]{e.getMessage()}, e);
        } finally {
            try {
                FileUtil.del(filePath);
            } catch (Exception e) { // impl-contract: best-effort - cleanup failure must not hide the diff result or original failure.
                log.error("delete file error", e);
            }
        }
    }

    private ConnectInfo buildConnectInfo(DbConnectionDiffRequest param) {
        WorkspaceDataSource dataSource = workspaceDataSourceService.queryDisplayDataSourceById(param.getDataSourceId(), true);
        if (dataSource == null) {
            throw new BusinessException("datasource.not.found");
        }
        return connectionContextConverter.datasource2connectInfo(param, dataSource,
                JdbcUrlUtils.resetUrl(dataSource.getUrl(), dataSource.getType(), dataSource.getServiceType()));
    }

    private Database initializeDatabase(Connection conn, ConnectInfo connectInfo) throws DatabaseException {
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(conn));
        if (StringUtils.isNotBlank(connectInfo.getSchemaName())) {
            database.setDefaultSchemaName(connectInfo.getSchemaName());
        }
        if (StringUtils.isNotBlank(connectInfo.getDatabaseName())) {
            database.setDefaultCatalogName(connectInfo.getDatabaseName());
        }
        return database;
    }

    private DiffResult generateDiff(Database sourceDatabase, Database targetDatabase) throws LiquibaseException {
        CompareControl compareControl = new CompareControl();
        return liquibase.diff.DiffGeneratorFactory.getInstance()
                .compare(sourceDatabase, targetDatabase, compareControl);
    }


    private void generateChangeLog(DiffResult diffResult, String diffFilePath, String targetDbType)
            throws DatabaseException, IOException, ParserConfigurationException {
        DiffOutputControl diffOutputControl = new DiffOutputControl();
        diffOutputControl.setIncludeCatalog(false);
        diffOutputControl.setIncludeSchema(false);
        diffOutputControl.setIncludeTablespace(false);

        Chat2dbDiffToChangeLog diffToChangeLog = new Chat2dbDiffToChangeLog(
                diffResult, diffOutputControl, Chat2DBContext.getDiffChangeSetProcessor(targetDbType));
        diffToChangeLog.setChangeSetAuthor("Chat2DB client");
        diffToChangeLog.print(diffFilePath);
    }


    private void configureLiquibaseTrackingTables(Database targetDatabase, String databaseChangeLogTableName, String databaseChangeLogLockTableName) {
        targetDatabase.setDatabaseChangeLogTableName(databaseChangeLogTableName);
        targetDatabase.setDatabaseChangeLogLockTableName(databaseChangeLogLockTableName);
    }

    private String executeLiquibaseUpdate(Database targetDatabase, FileSystemResourceAccessor resourceAccessor, StringWriter stringWriter) throws LiquibaseException {
        Liquibase liquibase = new Liquibase(DIFF_FILE, resourceAccessor, targetDatabase);
        liquibase.getDatabase().setAutoCommit(false);
        liquibase.update("", stringWriter);
        return stringWriter.toString();
    }

    private String filter(String s, String dbType, String tableName, String tableName2) {
        DbType type = JdbcUtils.parse2DruidDbType(dbType);
        List<String> sql = SqlUtils.parse(s, type, true);
        StringBuilder sb = new StringBuilder();
        if (CollectionUtils.isNotEmpty(sql)) {
            for (String s1 : sql) {
                if (StringUtils.isNotBlank(s1) && !s1.contains(tableName) && !s1.contains(tableName2)) {
                    sb.append(s1).append(";\n\n");
                }
            }
        }
        return sb.toString();
    }
}
