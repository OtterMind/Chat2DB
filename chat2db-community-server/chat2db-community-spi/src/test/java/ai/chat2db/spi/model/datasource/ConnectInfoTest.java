package ai.chat2db.spi.model.datasource;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.datasource.KeyValue;
import ai.chat2db.community.domain.api.model.datasource.SSHInfo;
import ai.chat2db.community.domain.api.model.datasource.SSLInfo;
import com.jcraft.jsch.JSch;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectInfoTest {

    private static final Set<String> COPIED_FIELDS = Set.of(
            "loginUser", "alias", "dataSourceId", "databaseName", "schemaName", "consoleId", "url", "user",
            "password", "consoleOwn", "dbType", "port", "urlWithOutDatabase", "host", "ssh", "ssl", "sid",
            "driver", "jdbc", "extendInfo", "serviceName", "keyfile", "email", "project", "dbVersion",
            "driverConfig"
    );

    private static final Set<String> OMITTED_METADATA_FIELDS = Set.of("gmtCreate", "gmtModified");

    private static final Set<String> RESET_RUNTIME_FIELDS = Set.of(
            "connection", "lastAccessTime", "poolGeneration", "session", "inUse"
    );

    @Test
    void equalInstancesShouldHaveEqualHashCodes() {
        LocalDateTime modifiedAt = LocalDateTime.of(2026, 7, 25, 12, 0);
        ConnectInfo first = new ConnectInfo();
        first.setDataSourceId(42L);
        first.setGmtModified(modifiedAt);
        first.setConsoleId(1L);
        first.setDatabaseName("first_database");
        ConnectInfo second = new ConnectInfo();
        second.setDataSourceId(42L);
        second.setGmtModified(modifiedAt);
        second.setConsoleId(2L);
        second.setDatabaseName("second_database");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void copyShouldPreserveConnectionFieldsAndResetRuntimeState() throws Exception {
        ConnectInfo source = populatedConnectInfo();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> null);
        source.setConnection(connection);
        source.setSession(new JSch().getSession("ssh-user", "localhost", 22));
        source.setLastAccessTime(new Date(1L));
        source.updatePoolGeneration(12L);
        assertTrue(source.trySetInUse());

        long beforeCopy = System.currentTimeMillis();
        ConnectInfo copy = source.copy();
        long afterCopy = System.currentTimeMillis();

        assertAll(
                () -> assertEquals(source.getLoginUser(), copy.getLoginUser()),
                () -> assertEquals(source.getAlias(), copy.getAlias()),
                () -> assertEquals(source.getDataSourceId(), copy.getDataSourceId()),
                () -> assertNull(copy.getGmtCreate()),
                () -> assertNull(copy.getGmtModified()),
                () -> assertEquals(source.getDatabaseName(), copy.getDatabaseName()),
                () -> assertEquals(source.getSchemaName(), copy.getSchemaName()),
                () -> assertEquals(source.getConsoleId(), copy.getConsoleId()),
                () -> assertEquals(source.getUrl(), copy.getUrl()),
                () -> assertEquals(source.getUser(), copy.getUser()),
                () -> assertEquals(source.getPassword(), copy.getPassword()),
                () -> assertEquals(source.getConsoleOwn(), copy.getConsoleOwn()),
                () -> assertEquals(source.getDbType(), copy.getDbType()),
                () -> assertEquals(source.getPort(), copy.getPort()),
                () -> assertEquals(source.getUrlWithOutDatabase(), copy.getUrlWithOutDatabase()),
                () -> assertEquals(source.getHost(), copy.getHost()),
                () -> assertEquals(source.getSsh(), copy.getSsh()),
                () -> assertEquals(source.getSid(), copy.getSid()),
                () -> assertEquals(source.getDriver(), copy.getDriver()),
                () -> assertEquals(source.getJdbc(), copy.getJdbc()),
                () -> assertEquals(source.getExtendInfo(), copy.getExtendInfo()),
                () -> assertEquals(source.getServiceName(), copy.getServiceName()),
                () -> assertEquals(source.getKeyfile(), copy.getKeyfile()),
                () -> assertEquals(source.getEmail(), copy.getEmail()),
                () -> assertEquals(source.getProject(), copy.getProject()),
                () -> assertEquals(source.getDbVersion(), copy.getDbVersion()),
                () -> assertEquals(source.getDriverConfig(), copy.getDriverConfig()),
                () -> assertNull(copy.getConnection()),
                () -> assertNull(copy.getSession()),
                () -> assertNull(copy.poolGeneration()),
                () -> assertFalse(copy.isInUse()),
                () -> assertTrue(copy.getLastAccessTime().getTime() >= beforeCopy),
                () -> assertTrue(copy.getLastAccessTime().getTime() <= afterCopy)
        );
    }

    @Test
    void copyShouldNotShareMutableConnectionConfiguration() {
        ConnectInfo source = populatedConnectInfo();

        ConnectInfo copy = source.copy();

        assertAll(
                () -> assertNotSame(source.getSsh(), copy.getSsh()),
                () -> assertNotSame(source.getSsl(), copy.getSsl()),
                () -> assertNotSame(source.getExtendInfo(), copy.getExtendInfo()),
                () -> assertNotSame(source.getExtendInfo().get(0), copy.getExtendInfo().get(0)),
                () -> assertNotSame(source.getExtendInfo().get(0).getChoices(), copy.getExtendInfo().get(0).getChoices()),
                () -> assertNotSame(source.getDriverConfig(), copy.getDriverConfig()),
                () -> assertNotSame(source.getDriverConfig().getDownloadJdbcDriverUrls(),
                        copy.getDriverConfig().getDownloadJdbcDriverUrls()),
                () -> assertNotSame(source.getDriverConfig().getExtendInfo(), copy.getDriverConfig().getExtendInfo()),
                () -> assertNotSame(source.getDriverConfig().getExtendInfo().get(0),
                        copy.getDriverConfig().getExtendInfo().get(0))
        );

        copy.getSsh().setHostName("changed-host");
        copy.getExtendInfo().get(0).setValue("changed-value");
        copy.getExtendInfo().get(0).getChoices().add("changed-choice");
        copy.getDriverConfig().getDownloadJdbcDriverUrls().add("https://example.com/changed.jar");
        copy.getDriverConfig().getExtendInfo().get(0).setValue("changed-driver-value");

        assertAll(
                () -> assertEquals("ssh.example.com", source.getSsh().getHostName()),
                () -> assertEquals("extension-value", source.getExtendInfo().get(0).getValue()),
                () -> assertEquals(List.of("one", "two"), source.getExtendInfo().get(0).getChoices()),
                () -> assertEquals(List.of("https://example.com/driver.jar"),
                        source.getDriverConfig().getDownloadJdbcDriverUrls()),
                () -> assertEquals("driver-extension-value",
                        source.getDriverConfig().getExtendInfo().get(0).getValue())
        );
    }

    @Test
    void everyInstanceFieldShouldHaveExplicitCopySemantics() {
        Set<String> actualFields = Arrays.stream(ConnectInfo.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName())
                .collect(Collectors.toSet());
        Set<String> classifiedFields = new HashSet<>(COPIED_FIELDS);
        classifiedFields.addAll(OMITTED_METADATA_FIELDS);
        classifiedFields.addAll(RESET_RUNTIME_FIELDS);

        assertEquals(classifiedFields, actualFields);
    }

    private ConnectInfo populatedConnectInfo() {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setLoginUser("login-user");
        connectInfo.setAlias("primary");
        connectInfo.setDataSourceId(42L);
        connectInfo.setGmtCreate(LocalDateTime.of(2026, 8, 28, 9, 0));
        connectInfo.setGmtModified(LocalDateTime.of(2026, 8, 29, 10, 0));
        connectInfo.setDatabaseName("inventory");
        connectInfo.setSchemaName("public");
        connectInfo.setConsoleId(7L);
        connectInfo.setUrl("jdbc:test://db.example.com/inventory");
        connectInfo.setUser("database-user");
        connectInfo.setPassword("database-password");
        connectInfo.setConsoleOwn(Boolean.TRUE);
        connectInfo.setDbType("TEST");
        connectInfo.setPort(15432);
        connectInfo.setUrlWithOutDatabase("jdbc:test://db.example.com");
        connectInfo.setHost("db.example.com");
        connectInfo.setSsh(sshInfo());
        connectInfo.setSsl(new SSLInfo());
        connectInfo.setSid("database-sid");
        connectInfo.setDriver("test-driver");
        connectInfo.setJdbc("test-jdbc");
        connectInfo.setExtendInfo(new ArrayList<>(List.of(
                keyValue("extension", "extension-value", true, List.of("one", "two")))));
        connectInfo.setServiceName("database-service");
        connectInfo.setKeyfile("C:/keys/service-account.json");
        connectInfo.setEmail("service-account@example.com");
        connectInfo.setProject("cloud-project");
        connectInfo.setDbVersion("1.0");
        connectInfo.setDriverConfig(driverConfig());
        return connectInfo;
    }

    private SSHInfo sshInfo() {
        SSHInfo ssh = new SSHInfo();
        ssh.setUse(true);
        ssh.setHostName("ssh.example.com");
        ssh.setPort("22");
        ssh.setUserName("ssh-user");
        ssh.setLocalPort("15432");
        ssh.setAuthenticationType("PASSWORD");
        ssh.setPassword("ssh-password");
        ssh.setKeyFile("C:/keys/ssh.pem");
        ssh.setPassphrase("ssh-passphrase");
        ssh.setRHost("db.internal");
        ssh.setRPort("5432");
        return ssh;
    }

    private DriverConfig driverConfig() {
        DriverConfig config = new DriverConfig();
        config.setUrl("jdbc:test://{host}:{port}/{database}");
        config.setJdbcDriver("test-driver.jar");
        config.setJdbcDriverClass("example.Driver");
        config.setDownloadJdbcDriverUrls(new ArrayList<>(List.of("https://example.com/driver.jar")));
        config.setDbType("TEST");
        config.setCustom(true);
        config.setExtendInfo(new ArrayList<>(List.of(
                keyValue("driver-extension", "driver-extension-value", false, List.of("alpha", "beta")))));
        config.setDefaultDriver(true);
        return config;
    }

    private KeyValue keyValue(String key, String value, boolean required, List<String> choices) {
        KeyValue keyValue = new KeyValue();
        keyValue.setKey(key);
        keyValue.setValue(value);
        keyValue.setRequired(required);
        keyValue.setChoices(new ArrayList<>(choices));
        return keyValue;
    }
}
