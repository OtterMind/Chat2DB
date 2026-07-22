
package ai.chat2db.spi.model.datasource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ObjectUtils;


@Slf4j
public class ConnectInfo {

    private String loginUser;


    private String alias;


    private Long dataSourceId;


    private LocalDateTime gmtCreate;


    private LocalDateTime gmtModified;


    private String databaseName;


    private String schemaName;


    private Long consoleId;


    private String url;


    private String user;


    private String password;


    private Boolean consoleOwn = Boolean.FALSE;


    private String dbType;


    private Integer port;


    private String urlWithOutDatabase;


    private String host;


    private SSHInfo ssh;


    private SSLInfo ssl;


    private String sid;


    private String driver;


    private String jdbc;


    private List<KeyValue> extendInfo;


    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    private String serviceName;


    public Connection connection;


    public String getKeyfile() {
        return keyfile;
    }

    public void setKeyfile(String keyfile) {
        this.keyfile = keyfile;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    private String keyfile;

    private String email;

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    private String project;


    private String dbVersion;


    private DriverConfig driverConfig;


    private Date lastAccessTime;


    public String getDbVersion() {
        return dbVersion;
    }

    public void setDbVersion(String dbVersion) {
        this.dbVersion = dbVersion;
    }

    public DriverConfig getDriverConfig() {
        return driverConfig;
    }


    public void setDriverConfig(DriverConfig driverConfig) {
        this.driverConfig = driverConfig;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public Session session;


    public LinkedHashMap<String, Object> getExtendMap() {

        if (ObjectUtils.isEmpty(extendInfo)) {
            if (driverConfig != null) {
                extendInfo = driverConfig.getExtendInfo();
            } else {
                return new LinkedHashMap<>();
            }
        }
        if (ObjectUtils.isEmpty(extendInfo)) {
            return new LinkedHashMap<>();
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (KeyValue keyValue : extendInfo) {
            map.put(keyValue.getKey(), keyValue.getValue());
        }
        return map;
    }


    public void setDatabase(String database) {
        this.databaseName = database;
    }


    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConnectInfo)) {
            return false;
        }
        ConnectInfo that = (ConnectInfo) o;
        return Objects.equals(dataSourceId, that.dataSourceId)
                && Objects.equals(gmtModified, that.gmtModified)
                ;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataSourceId, gmtModified);
    }

    public Long getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(Long dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public Long getConsoleId() {
        return consoleId;
    }

    public void setConsoleId(Long consoleId) {
        this.consoleId = consoleId;
    }

    public String getUrl() {
        return url;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }


    public void setPassword(String password) {
        this.password = password;
    }


    public Boolean getConsoleOwn() {
        return consoleOwn;
    }


    public void setConsoleOwn(Boolean consoleOwn) {
        this.consoleOwn = consoleOwn;
    }


    public String getDbType() {
        return dbType;
    }


    public void setDbType(String dbType) {
        this.dbType = dbType;
    }


    public Integer getPort() {
        return port;
    }


    public void setPort(Integer port) {
        this.port = port;
    }


    public String getUrlWithOutDatabase() {
        return urlWithOutDatabase;
    }


    public void setUrlWithOutDatabase(String urlWithOutDatabase) {
        this.urlWithOutDatabase = urlWithOutDatabase;
    }


    public String getHost() {
        return host;
    }


    public void setHost(String host) {
        this.host = host;
    }


    public SSHInfo getSsh() {
        return ssh;
    }


    public void setSsh(SSHInfo ssh) {
        this.ssh = ssh;
    }


    public SSLInfo getSsl() {
        return ssl;
    }


    public void setSsl(SSLInfo ssl) {
        this.ssl = ssl;
    }


    public String getSid() {
        return sid;
    }


    public void setSid(String sid) {
        this.sid = sid;
    }


    public String getDriver() {
        return driver;
    }


    public void setDriver(String driver) {
        this.driver = driver;
    }


    public String getJdbc() {
        return jdbc;
    }


    public void setJdbc(String jdbc) {
        this.jdbc = jdbc;
    }


    public List<KeyValue> getExtendInfo() {
        return extendInfo;
    }


    public void setExtendInfo(List<KeyValue> extendInfo) {
        this.extendInfo = extendInfo;
    }


    public Connection getConnection() {
        return connection;
    }


    public void setConnection(Connection connection) {
        this.connection = connection;
    }


    public String getAlias() {
        return alias;
    }


    public void setAlias(String alias) {
        this.alias = alias;
    }

    public LocalDateTime getGmtCreate() {
        return gmtCreate;
    }

    public void setGmtCreate(LocalDateTime gmtCreate) {
        this.gmtCreate = gmtCreate;
    }

    public LocalDateTime getGmtModified() {
        return gmtModified;
    }

    public void setGmtModified(LocalDateTime gmtModified) {
        this.gmtModified = gmtModified;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public ConnectInfo copy() {
        ConnectInfo copy = createCopy();
        copy.setDbVersion(this.getDbVersion());
        copy.setDbType(this.getDbType());
        copy.setHost(this.getHost());
        copy.setPort(this.getPort());
        copy.setDatabaseName(this.getDatabaseName());
        copy.setSchemaName(this.getSchemaName());
        copy.setUser(this.getUser());
        copy.setPassword(this.getPassword());
        copy.setUrl(this.getUrl());
        copy.setAlias(this.getAlias());
        copy.setDataSourceId(this.getDataSourceId());
        copy.setConsoleId(this.getConsoleId());
        copy.setConsoleOwn(this.getConsoleOwn());
        copy.setDriver(this.getDriver());
        copy.setSsh(this.getSsh());
        copy.setSsl(this.getSsl());
        copy.setJdbc(this.getJdbc());
        copy.setExtendInfo(this.getExtendInfo());
        copy.setDriverConfig(this.getDriverConfig());
        copy.setSid(this.getSid());
        copy.setUrlWithOutDatabase(this.getUrlWithOutDatabase());
        copy.setLastAccessTime(new Date());
        return copy;
    }

    protected ConnectInfo createCopy() {
        return new ConnectInfo();
    }

    public void close() {
        if (this != null) {
            Connection connection = this.getConnection();
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                    log.info("connection close success");
                }
            } catch (SQLException e) {
                log.error("connection close error", e);
            }
            com.jcraft.jsch.Session session = this.getSession();
            if (session != null && session.isConnected() && this.getSsh() != null
                    && this.getSsh().isUse()) {
                try {
                    session.delPortForwardingL(Integer.parseInt(this.getSsh().getLocalPort()));
                } catch (JSchException e) {
                    log.error("ssh close error", e);
                }
            }
        }
    }


    public String getKey() {
        return "loginUser:" + loginUser + "_dataSourceId:" + dataSourceId + "_databaseName:" + databaseName + "_schemaName:" + schemaName + "_consoleId:" + consoleId;
    }

    public String getLoginUser() {
        return loginUser;
    }

    public void setLoginUser(String loginUser) {
        this.loginUser = loginUser;
    }

    public Date getLastAccessTime() {
        return lastAccessTime;
    }

    public void setLastAccessTime(Date lastAccessTime) {
        this.lastAccessTime = lastAccessTime;
    }

    private AtomicBoolean inUse = new AtomicBoolean(false);

    public boolean trySetInUse() {
        boolean flag = inUse.compareAndSet(false, true);
        if (flag) {
            setLastAccessTime(new Date());
        }
        return flag;
    }

    public void releaseInUse() {
        inUse.set(false);
    }

    public boolean isInUse() {
        return inUse.get();
    }
}
