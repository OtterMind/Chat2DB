package ai.chat2db.community.web.api.aspect.connection;

import ai.chat2db.community.domain.api.model.request.runtime.DbConnectionContextRequest;
import ai.chat2db.community.domain.api.service.db.IDbConnectionContextService;
import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceBaseRequestInfo;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceConsoleRequestInfo;
import ai.chat2db.community.web.api.model.request.data.source.IDataSourceSchemaRequestInfo;
import ai.chat2db.community.web.api.util.ApplicationContextUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;


@Component
@Aspect
@Slf4j
@Order(20)
public class ConnectionInfoHandler {

    @Autowired
    private IDbConnectionContextService connectionContextService;

    @Around("within(@ai.chat2db.community.web.api.aspect.connection.ConnectionInfoAspect *)")
    public Object connectionInfoHandler(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        try {
            Object[] params = proceedingJoinPoint.getArgs();
            if (params != null && params.length > 0) {
                for (int i = 0; i < params.length; i++) {
                    Object param = params[i];
                    if (param instanceof DataSourceBaseRequest) {
                        Long dataSourceId = ((DataSourceBaseRequest) param).getDataSourceId();
                        String schemaName = ((DataSourceBaseRequest) param).getSchemaName();
                        String database = ((DataSourceBaseRequest) param).getDatabaseName();
                        if (dataSourceId != null && dataSourceId >1L) {
                            bind(dataSourceId, database, null, schemaName);
                        }else {
                            customConnectionInfo(dataSourceId, database, null, schemaName);
                        }
                    } else if (param instanceof IDataSourceSchemaRequestInfo) {
                        Long dataSourceId = ((IDataSourceSchemaRequestInfo) param).getDataSourceId();
                        String schemaName = ((IDataSourceSchemaRequestInfo) param).getSchemaName();
                        String database = ((IDataSourceSchemaRequestInfo) param).getDatabaseName();
                        if (dataSourceId != null && dataSourceId > 1L) {
                            bind(dataSourceId, database, null, schemaName);
                        } else {
                            customConnectionInfo(dataSourceId, database, null, schemaName);
                        }
                    } else if (param instanceof IDataSourceConsoleRequestInfo) {
                        Long dataSourceId = ((IDataSourceConsoleRequestInfo) param).getDataSourceId();
                        Long consoleId = ((IDataSourceConsoleRequestInfo) param).getConsoleId();
                        String database = ((IDataSourceConsoleRequestInfo) param).getDatabaseName();
                        if (dataSourceId != null && dataSourceId >1L) {
                            bind(dataSourceId, database, consoleId, null);
                        }else {
                            customConnectionInfo(dataSourceId, database, consoleId,null);
                        }
                    } else if (param instanceof IDataSourceBaseRequestInfo) {
                        Long dataSourceId = ((IDataSourceBaseRequestInfo) param).getDataSourceId();
                        String database = ((IDataSourceBaseRequestInfo) param).getDatabaseName();
                        if (dataSourceId != null && dataSourceId > 1L) {
                            bind(dataSourceId, database, null, null);
                        }else {
                            customConnectionInfo(dataSourceId, database, null,null);
                        }
                    }
                }
            }
            return proceedingJoinPoint.proceed();
        } finally {
            connectionContextService.clear();
        }
    }

    private void bind(Long dataSourceId, String database, Long consoleId, String schemaName) {
        DbConnectionContextRequest param = new DbConnectionContextRequest();
        param.setDataSourceId(dataSourceId);
        param.setDatabaseName(database);
        param.setConsoleId(consoleId);
        param.setSchemaName(schemaName);
        connectionContextService.bind(param);
    }
    private void customConnectionInfo(Long dataSourceId, String database, Long consoleId, String schemaName) {
        if (Objects.isNull(dataSourceId) || dataSourceId < 1L) {
            return;
        }
        try {
            if (ApplicationContextUtil.getApplicationContext() == null) {
                return;
            }
            ICustomConnection connection = ApplicationContextUtil.getApplicationContext()
                    .getBeanProvider(ICustomConnection.class)
                    .getIfAvailable();
            if (connection == null) {
                return;
            }
            DbConnectionContextRequest param = connection.getConnectionInfo(dataSourceId, database, schemaName, consoleId);
            if (param != null) {
                connectionContextService.bind(param);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve custom connection info for dataSourceId={}, database={}, schemaName={}, consoleId={}",
                    dataSourceId, database, schemaName, consoleId, e);
        }
    }

}
