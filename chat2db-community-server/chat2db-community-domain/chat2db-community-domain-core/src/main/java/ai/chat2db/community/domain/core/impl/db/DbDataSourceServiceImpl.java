package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourceCloseRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourcePreConnectRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDataSourceTestRequest;
import ai.chat2db.community.domain.api.model.request.datasource.DbDatabaseQueryAllRequest;
import ai.chat2db.community.domain.api.service.db.IDbDataSourceService;
import ai.chat2db.community.domain.core.converter.DataSourceConverter;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.exception.ConnectionException;
import ai.chat2db.community.tools.util.ConfigUtils;
import ai.chat2db.community.tools.util.JdbcUrlUtils;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.datasource.DataSourceConnect;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.sql.ConnectionPool;
import ai.chat2db.spi.sql.MySqlTlsTranslator;
import ai.chat2db.spi.ssh.SSHManager;
import ai.chat2db.spi.util.JdbcUtils;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;


@Slf4j
@Service
public class DbDataSourceServiceImpl implements IDbDataSourceService {

    @Autowired
    private DataSourceConverter dataSourceConverter;


    @Override
    public void preConnect(DbDataSourcePreConnectRequest param) {
        param.setUrl(JdbcUrlUtils.resetUrl(param.getUrl(), param.getType(), param.getServiceType()));
        DbDataSourceTestRequest testParam
                = dataSourceConverter.param2param(param);
        validate(param);
        DriverConfig driverConfig = testParam.getDriverConfig();
        if (driverConfig == null || !driverConfig.notEmpty()) {
            driverConfig = Chat2DBContext.getDefaultDriverConfig(param.getType());
        }
        Map<String, Object> map = KeyValue.toMap(param.getExtendInfo());
        if(StringUtils.isNotBlank(param.getProject())){
            map.put("ProjectId",param.getProject());
        }
        if(StringUtils.isNotBlank(param.getEmail())){
            map.put("OAuthServiceAcctEmail",param.getEmail());
        }
        if(StringUtils.isNotBlank(param.getKeyfile())){
            map.put("OAuthType","0");
            map.put("OAuthPvtKeyPath",param.getKeyfile());
        }
        MySqlTlsTranslator.apply(param.getSsl(), driverConfig, map);

        DataSourceConnect dataSourceConnect = JdbcUtils.testConnect(testParam.getUrl(), testParam.getHost(),
                testParam.getPort(),
                testParam.getUsername(), testParam.getPassword(), testParam.getDbType(),
                driverConfig, param.getSsh(),map);
        if (BooleanUtils.isNotTrue(dataSourceConnect.getSuccess())) {
            throw new BusinessException(dataSourceConnect.getMessage(),
                    new Object[]{dataSourceConnect.getDescription(), dataSourceConnect.getErrorDetail()});
        }
    }

    @Override
    public List<Database> connect(Long id) {
        DbDatabaseQueryAllRequest queryAllParam = new DbDatabaseQueryAllRequest();
        queryAllParam.setDataSourceId(id);
        return Chat2DBContext.getDbMetaData().databases(Chat2DBContext.getConnection());
    }

    @Override
    public void close(Long id) {
        DbDataSourceCloseRequest closeParam = new DbDataSourceCloseRequest();
        closeParam.setDataSourceId(id);
    }

    @Override
    public DriverConfig defaultDriverConfig(String dbType) {
        return Chat2DBContext.getDefaultDriverConfig(dbType);
    }

    @Override
    public void removeConnection(Long id) {
        ConnectionPool.removeConnection(id);
    }

    @Override
    public void testSshConnection(SSHInfo ssh) {
        Session session = null;
        try {
            session = SSHManager.getSSHSession(ssh);
        } catch (Exception e) {
            throw new ConnectionException("connection.ssh.error", null, e);
        } finally {
            if (session != null) {
                session.disconnect();
            }
        }
    }

    @Override
    public void closeRuntime() {
        Chat2DBContext.close();
        SSHManager.close();
    }

    private void validate(DbDataSourcePreConnectRequest param) {
        if (!ConfigUtils.isDesktop() && ConfigUtils.isRelease()) {
            if ("LocalFile".equalsIgnoreCase(param.getServiceType())
                    && ("H2".equalsIgnoreCase(param.getType()) || "SQLite".equalsIgnoreCase(param.getType()))) {
                throw new BusinessException("web.not.support.db.type");
            }
        }
    }

}
