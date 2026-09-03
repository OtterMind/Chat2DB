package ai.chat2db.community.domain.core.impl.cli;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.PageResponse;
import ai.chat2db.community.domain.api.model.cli.CliConnectionTestResponse;
import ai.chat2db.community.domain.api.model.cli.CliDataSource;
import ai.chat2db.community.domain.api.model.cli.CliPage;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import ai.chat2db.community.domain.api.model.request.cli.CliConnectionTestRequest;
import ai.chat2db.community.domain.api.model.request.cli.CliDataSourceCreateRequest;
import ai.chat2db.community.domain.api.model.request.cli.CliDataSourceListRequest;
import ai.chat2db.community.domain.api.model.request.cli.CliDataSourceUpdateRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePageQueryRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.service.cli.ICliDataSourceService;
import ai.chat2db.community.domain.api.service.db.IDbDataSourceService;
import ai.chat2db.community.domain.api.service.db.IDbWorkspaceDataSourceService;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceStorageFacade;
import ai.chat2db.community.domain.core.converter.CliDataSourceConverter;
import ai.chat2db.community.tools.exception.cli.CliErrorMessages;
import ai.chat2db.community.tools.exception.cli.CliDomainException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.JdbcUrlUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class CliDataSourceServiceImpl implements ICliDataSourceService {

    private static final String REDACTED = "<redacted>";

    private final IWorkspaceStorageFacade workspaceStorageFacade;
    private final IDbWorkspaceDataSourceService workspaceDataSourceService;
    private final IDbDataSourceService dataSourceService;
    private final CliDataSourceConverter cliDataSourceConverter;

    public CliDataSourceServiceImpl(IWorkspaceStorageFacade workspaceStorageFacade,
            IDbWorkspaceDataSourceService workspaceDataSourceService, IDbDataSourceService dataSourceService,
            CliDataSourceConverter cliDataSourceConverter) {
        this.workspaceStorageFacade = workspaceStorageFacade;
        this.workspaceDataSourceService = workspaceDataSourceService;
        this.dataSourceService = dataSourceService;
        this.cliDataSourceConverter = cliDataSourceConverter;
    }

    @Override
    public CliPage<CliDataSource> list(CliDataSourceListRequest request) {
        DbDataSourcePageQueryRequest queryParam = new DbDataSourcePageQueryRequest();
        queryParam.setPageNo(request.safePageNo());
        queryParam.setPageSize(request.safePageSize());
        queryParam.setSearchKey(request.getSearchKey());

        PageResponse<WorkspaceDataSource> result = workspaceStorageFacade.listDataSources(queryParam);
        List<WorkspaceDataSource> data = result == null || result.getData() == null
                ? Collections.emptyList()
                : result.getData();
        Long total = result == null || result.getTotal() == null ? 0L : result.getTotal();
        return CliPage.of(cliDataSourceConverter.datasource2response(data), request.safePageNo(), request.safePageSize(), total);
    }

    @Override
    public CliDataSource get(Long dataSourceId) {
        WorkspaceDataSource result = workspaceStorageFacade.queryDataSourceById(dataSourceId, false);
        return cliDataSourceConverter.datasource2response(requireDatasource(dataSourceId, result));
    }

    @Override
    public CliConnectionTestResponse connectionTest(CliConnectionTestRequest request) {
        long startedAt = System.currentTimeMillis();
        validateConnectionTestRequest(request);
        normalizeConnectionTestRequest(request);
        DbDataSourcePreConnectRequest param = buildPreConnectParam(request);
        CliConnectionTestResponse response = new CliConnectionTestResponse();
        response.setMode(request.getDataSourceId() == null ? "temporary" : "existing");
        response.setDataSourceId(request.getDataSourceId());
        response.setDbType(param.getType());
        try {
            dataSourceService.preConnect(param);
            response.setCanConnect(Boolean.TRUE);
        } catch (Exception exception) {
            log.error("CLI datasource connection test failed, exceptionType={}",
                    exception.getClass().getName());
            response.setCanConnect(Boolean.FALSE);
            response.setErrorCode(CliErrorMessages.DATASOURCE_CONNECTION_FAILED);
            response.setErrorMessage(CliErrorMessages.DATASOURCE_CONNECTION_FAILED_MESSAGE);
        } finally {
            response.setDurationMs(System.currentTimeMillis() - startedAt);
        }
        return response;
    }

    @Override
    public CliDataSource create(CliDataSourceCreateRequest request) {
        validateCreateRequest(request);
        normalizeCreateDbType(request);
        normalizeCreateRequest(request);
        CliConnectionTestResponse connectionTest = preConnectForCreate(request);
        if (!Boolean.TRUE.equals(connectionTest.getCanConnect())) {
            throw new CliDomainException(CliErrorMessages.DATASOURCE_CONNECTION_FAILED,
                    CliErrorMessages.DATASOURCE_CONNECTION_FAILED_MESSAGE);
        }
        WorkspaceDataSource dataSource = cliDataSourceConverter.create2storage(request);
        validateCreate(dataSource);
        if (dataSource.getDriverConfig() == null || StringUtils.isBlank(dataSource.getDriverConfig().getJdbcDriverClass())) {
            dataSource.setDriverConfig(dataSourceService.defaultDriverConfig(dataSource.getType()));
        }
        Long dataSourceId = workspaceStorageFacade.createDataSource(dataSource);
        CliDataSource response = get(dataSourceId);
        enrichCreateResponse(response, request);
        return response;
    }

    @Override
    public CliDataSource update(CliDataSourceUpdateRequest request) {
        WorkspaceDataSource dataSource = cliDataSourceConverter.update2storage(request);
        workspaceStorageFacade.updateDataSource(dataSource);
        dataSourceService.removeConnection(request.getDataSourceId());
        return get(request.getDataSourceId());
    }

    @Override
    public void delete(Long dataSourceId) {
        requireDatasource(dataSourceId, workspaceStorageFacade.queryDataSourceById(dataSourceId, false));
        workspaceStorageFacade.deleteDataSource(dataSourceId);
        dataSourceService.removeConnection(dataSourceId);
    }

    private WorkspaceDataSource requireDatasource(Long dataSourceId, WorkspaceDataSource dataSource) {
        if (dataSource == null) {
            throw new CliDomainException("datasource_not_found", "Datasource not found: " + dataSourceId);
        }
        return dataSource;
    }

    private void validateConnectionTestRequest(CliConnectionTestRequest request) {
        boolean hasDatasourceId = request.getDataSourceId() != null;
        boolean hasTemporaryField = StringUtils.isNotBlank(request.getDbType())
                || StringUtils.isNotBlank(request.getUrl())
                || StringUtils.isNotBlank(request.getHost())
                || StringUtils.isNotBlank(request.getPort())
                || StringUtils.isNotBlank(request.getDatabase())
                || StringUtils.isNotBlank(request.getUser())
                || request.getPassword() != null
                || StringUtils.isNotBlank(request.getServiceName())
                || StringUtils.isNotBlank(request.getServiceType());
        if (hasDatasourceId) {
            if (hasTemporaryField) {
                throw new CliDomainException("invalid_connection_test_args",
                        "--data-source-id cannot be combined with temporary connection parameters");
            }
            return;
        }
        if (StringUtils.isBlank(request.getDbType())
                || StringUtils.isBlank(request.getUser())
                || StringUtils.isBlank(request.getPassword())) {
            throw new CliDomainException("invalid_connection_test_args",
                    "temporary mode requires dbType, user, and password");
        }
        boolean hasUrl = StringUtils.isNotBlank(request.getUrl());
        boolean hasHostFields = StringUtils.isNotBlank(request.getHost())
                || StringUtils.isNotBlank(request.getPort())
                || StringUtils.isNotBlank(request.getDatabase());
        if (hasUrl && hasHostFields) {
            throw new CliDomainException("invalid_connection_test_args",
                    "url cannot be combined with host, port, or database");
        }
        if (!hasUrl && !(StringUtils.isNotBlank(request.getHost())
                && StringUtils.isNotBlank(request.getPort())
                && StringUtils.isNotBlank(request.getDatabase()))) {
            throw new CliDomainException("invalid_connection_test_args",
                    "temporary mode requires either url or host, port, and database");
        }
    }

    private void normalizeConnectionTestRequest(CliConnectionTestRequest request) {
        request.setDbType(canonicalDbType(request.getDbType()));
    }

    private DbDataSourcePreConnectRequest buildPreConnectParam(CliConnectionTestRequest source) {
        if (source.getDataSourceId() != null) {
            return existingDatasourcePreConnectParam(source.getDataSourceId());
        }
        String url = JdbcUrlUtils.resetUrl(connectionTestUrl(source), source.getDbType(), source.getServiceType());
        return cliDataSourceConverter.connectionTest2param(source, url, defaultSsh(source.getSsh()),
                defaultDriverConfig(source.getDriverConfig(), source.getDbType()));
    }

    private void validateCreateRequest(CliDataSourceCreateRequest request) {
        if (StringUtils.isBlank(request.getDbType())) {
            throw new CliDomainException("invalid_datasource_create_args", "datasource-create requires dbType");
        }
        if (request.getEnvironmentId() == null) {
            throw new CliDomainException("invalid_datasource_create_args", "datasource-create requires environmentId");
        }
        if (request.getPassword() == null) {
            throw new CliDomainException("invalid_datasource_create_args", "datasource-create requires password");
        }
        boolean hasUrl = StringUtils.isNotBlank(request.getUrl());
        boolean hasHostFields = StringUtils.isNotBlank(request.getHost())
                || StringUtils.isNotBlank(request.getPort())
                || StringUtils.isNotBlank(request.getDatabase());
        if (hasUrl && hasHostFields) {
            throw new CliDomainException("invalid_datasource_create_args",
                    "url cannot be combined with host, port, or database");
        }
        if (!hasUrl && !(StringUtils.isNotBlank(request.getHost())
                && StringUtils.isNotBlank(request.getPort())
                && StringUtils.isNotBlank(request.getDatabase()))) {
            throw new CliDomainException("invalid_datasource_create_args",
                    "datasource-create requires either url or host, port, and database");
        }
    }

    private void normalizeCreateDbType(CliDataSourceCreateRequest request) {
        request.setDbType(canonicalDbType(request.getDbType()));
    }

    private void normalizeCreateRequest(CliDataSourceCreateRequest request) {
        if (StringUtils.isBlank(request.getUrl())) {
            request.setUrl(datasourceCreateUrl(request.getDbType(), request.getHost(), request.getPort(), request.getDatabase()));
        } else {
            ParsedJdbcUrl parsed = parseJdbcUrl(request.getUrl());
            if (StringUtils.isBlank(request.getHost())) {
                request.setHost(parsed.host);
            }
            if (StringUtils.isBlank(request.getPort())) {
                request.setPort(parsed.port);
            }
            if (StringUtils.isBlank(request.getDatabase())) {
                request.setDatabase(parsed.database);
            }
        }
        if (StringUtils.isBlank(request.getServiceName())) {
            request.setServiceName(request.getDatabase());
        }
        if (StringUtils.isBlank(request.getAlias())) {
            request.setAlias(defaultAlias(request));
        }
    }

    private CliConnectionTestResponse preConnectForCreate(CliDataSourceCreateRequest request) {
        long startedAt = System.currentTimeMillis();
        CliConnectionTestRequest connectionTestRequest = cliDataSourceConverter.create2connectionTest(request);
        DbDataSourcePreConnectRequest param = buildPreConnectParam(connectionTestRequest);
        CliConnectionTestResponse response = new CliConnectionTestResponse();
        response.setMode("temporary");
        response.setDbType(param.getType());
        try {
            dataSourceService.preConnect(param);
            response.setCanConnect(Boolean.TRUE);
        } catch (Exception exception) {
            log.error("CLI datasource create pre-connect failed, exceptionType={}",
                    exception.getClass().getName());
            response.setCanConnect(Boolean.FALSE);
            response.setErrorCode(CliErrorMessages.DATASOURCE_CONNECTION_FAILED);
            response.setErrorMessage(CliErrorMessages.DATASOURCE_CONNECTION_FAILED_MESSAGE);
        } finally {
            response.setDurationMs(System.currentTimeMillis() - startedAt);
        }
        return response;
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.isNotBlank(primary) ? primary : fallback;
    }

    private void validateCreate(WorkspaceDataSource request) {
        if (!ConfigUtils.isDesktop() && ConfigUtils.isRelease()
                && "LocalFile".equalsIgnoreCase(request.getServiceType())
                && ("H2".equalsIgnoreCase(request.getType()) || "SQLite".equalsIgnoreCase(request.getType()))) {
            throw new CliDomainException("web.not.support.db.type", "web.not.support.db.type");
        }
    }

    private void enrichCreateResponse(CliDataSource response, CliDataSourceCreateRequest source) {
        if (response == null) {
            return;
        }
        if (StringUtils.isBlank(response.getAlias())) {
            response.setAlias(source.getAlias());
        }
        if ((StringUtils.isBlank(response.getHost()) || REDACTED.equals(response.getHost()))
                && StringUtils.isNotBlank(source.getHost())) {
            response.setHost(source.getHost());
        }
        if (StringUtils.isBlank(response.getPort()) && StringUtils.isNotBlank(source.getPort())) {
            response.setPort(source.getPort());
        }
        if (StringUtils.isBlank(response.getDatabase())) {
            response.setDatabase(firstNonBlank(source.getServiceName(), source.getDatabase()));
        }
    }

    private String canonicalDbType(String dbType) {
        String normalized = StringUtils.upperCase(dbType);
        if ("POSTGRES".equals(normalized)) {
            return "POSTGRESQL";
        }
        return normalized;
    }

    private String defaultAlias(CliDataSourceCreateRequest request) {
        if (StringUtils.isNotBlank(request.getHost())) {
            return "@" + request.getHost();
        }
        return "@" + request.getDbType();
    }

    private DbDataSourcePreConnectRequest existingDatasourcePreConnectParam(Long dataSourceId) {
        WorkspaceDataSource dataSource = workspaceDataSourceService.queryDisplayDataSourceById(dataSourceId, true);
        if (dataSource == null) {
            throw new CliDomainException("datasource_not_found", "Datasource not found.");
        }
        String url = JdbcUrlUtils.resetUrl(dataSource.getUrl(), dataSource.getType(), dataSource.getServiceType());
        return cliDataSourceConverter.datasource2preConnect(dataSourceId, dataSource, url, defaultSsh(dataSource.getSsh()),
                defaultDriverConfig(dataSource.getDriverConfig(), dataSource.getType()));
    }

    private DriverConfig defaultDriverConfig(DriverConfig driverConfig, String dbType) {
        if (driverConfig == null || !driverConfig.notEmpty()) {
            return dataSourceService.defaultDriverConfig(dbType);
        }
        return driverConfig;
    }

    private SSHInfo defaultSsh(SSHInfo ssh) {
        return ssh == null ? new SSHInfo() : ssh;
    }

    private String connectionTestUrl(CliConnectionTestRequest source) {
        if (StringUtils.isNotBlank(source.getUrl())) {
            return source.getUrl();
        }
        return connectionUrl(source.getDbType(), source.getHost(), source.getPort(), source.getDatabase());
    }

    private String connectionUrl(String dbType, String host, String port, String database) {
        String normalizedDbType = StringUtils.upperCase(dbType);
        switch (normalizedDbType) {
            case "MYSQL":
                return String.format("jdbc:mysql://%s:%s/%s", host, port, database);
            case "MARIADB":
                return String.format("jdbc:mariadb://%s:%s/%s", host, port, database);
            case "TIDB":
            case "DORIS":
            case "STARROCKS":
                return String.format("jdbc:mysql://%s:%s/%s", host, port, database);
            case "POSTGRESQL":
            case "POSTGRES":
            case "COCKROACHDB":
            case "GAUSSDB":
                return String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
            case "OPENGAUSS":
                return String.format("jdbc:opengauss://%s:%s/%s", host, port, database);
            case "SQLSERVER":
                return String.format("jdbc:sqlserver://%s:%s;database=%s", host, port, database);
            case "ORACLE":
                return String.format("jdbc:oracle:thin:@%s:%s:%s", host, port, database);
            case "DB2":
                return String.format("jdbc:db2://%s:%s/%s", host, port, database);
            case "DM":
                return String.format("jdbc:dm://%s:%s/%s", host, port, database);
            case "KINGBASE":
                return String.format("jdbc:kingbase8://%s:%s/%s", host, port, database);
            case "CLICKHOUSE":
                return String.format("jdbc:clickhouse://%s:%s/%s", host, port, database);
            case "PRESTO":
                return String.format("jdbc:presto://%s:%s/%s", host, port, database);
            case "HIVE":
                return String.format("jdbc:hive2://%s:%s/%s", host, port, database);
            case "OCEANBASE":
            case "OCEANBASE_ORACLE":
                return String.format("jdbc:oceanbase://%s:%s/%s", host, port, database);
            case "MONGODB":
                return String.format("jdbc:mongodb://%s:%s/%s", host, port, database);
            case "REDIS":
                return String.format("jdbc:redis://%s:%s/%s", host, port, database);
            case "H2":
                return String.format("jdbc:h2:tcp://%s:%s/%s", host, port, database);
            case "REDSHIFT":
                return String.format("jdbc:redshift://%s:%s/%s", host, port, database);
            case "KYLIN":
                return String.format("jdbc:kylin://%s:%s/%s", host, port, database);
            case "OSCAR":
                return String.format("jdbc:oscar://%s:%s/%s", host, port, database);
            case "SUNDB":
                return String.format("jdbc:sundb://%s:%s/%s", host, port, database);
            case "XUGUDB":
                return String.format("jdbc:xugu://%s:%s/%s", host, port, database);
            case "TDENGINE":
                return String.format("jdbc:TAOS-RS://%s:%s/%s", host, port, database);
            default:
                break;
        }
        throw new CliDomainException("invalid_connection_test_args",
                "host, port, and database URL construction is not supported for " + normalizedDbType
                        + "; pass url instead");
    }

    private String datasourceCreateUrl(String dbType, String host, String port, String database) {
        try {
            return connectionUrl(dbType, host, port, database);
        } catch (CliDomainException exception) {
            throw new CliDomainException("invalid_datasource_create_args", exception.getMessage());
        }
    }

    private ParsedJdbcUrl parseJdbcUrl(String jdbcUrl) {
        if (StringUtils.isBlank(jdbcUrl) || !StringUtils.startsWithIgnoreCase(jdbcUrl, "jdbc:")) {
            return ParsedJdbcUrl.empty();
        }
        String url = StringUtils.removeStartIgnoreCase(jdbcUrl, "jdbc:");
        ParsedJdbcUrl sqlServer = parseSqlServerJdbcUrl(url);
        if (!sqlServer.isEmpty()) {
            return sqlServer;
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String port = uri.getPort() > 0 ? String.valueOf(uri.getPort()) : null;
            String database = StringUtils.removeStart(uri.getPath(), "/");
            if (StringUtils.contains(database, "?")) {
                database = StringUtils.substringBefore(database, "?");
            }
            return new ParsedJdbcUrl(host, port, StringUtils.trimToNull(database));
        } catch (URISyntaxException exception) { // impl-contract: fallback - unparsable JDBC URL simply contributes no host metadata.
            return ParsedJdbcUrl.empty();
        }
    }

    private ParsedJdbcUrl parseSqlServerJdbcUrl(String url) {
        if (!StringUtils.startsWithIgnoreCase(url, "sqlserver://")) {
            return ParsedJdbcUrl.empty();
        }
        String withoutPrefix = StringUtils.removeStartIgnoreCase(url, "sqlserver://");
        String authority = StringUtils.substringBefore(withoutPrefix, ";");
        String host = StringUtils.substringBefore(authority, ":");
        String port = StringUtils.substringAfter(authority, ":");
        if (StringUtils.equals(port, authority)) {
            port = null;
        }
        String database = null;
        String[] options = StringUtils.split(StringUtils.substringAfter(withoutPrefix, ";"), ';');
        if (options != null) {
            for (String segment : options) {
                if (StringUtils.startsWithIgnoreCase(segment, "database=")
                        || StringUtils.startsWithIgnoreCase(segment, "databaseName=")) {
                    database = StringUtils.substringAfter(segment, "=");
                    break;
                }
            }
        }
        return new ParsedJdbcUrl(StringUtils.trimToNull(host), StringUtils.trimToNull(port),
                StringUtils.trimToNull(database));
    }

    private static class ParsedJdbcUrl {

        private final String host;
        private final String port;
        private final String database;

        private ParsedJdbcUrl(String host, String port, String database) {
            this.host = host;
            this.port = port;
            this.database = database;
        }

        private static ParsedJdbcUrl empty() {
            return new ParsedJdbcUrl(null, null, null);
        }

        private boolean isEmpty() {
            return StringUtils.isBlank(host) && StringUtils.isBlank(port) && StringUtils.isBlank(database);
        }
    }
}
