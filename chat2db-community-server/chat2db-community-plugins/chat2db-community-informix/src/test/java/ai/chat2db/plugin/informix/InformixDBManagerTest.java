package ai.chat2db.plugin.informix;

import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InformixDBManagerTest {

    private static final String BASE_URL = "jdbc:informix-sqli://localhost:1533/testdb";

    private final InformixDBManager dbManager = new InformixDBManager();

    /**
     * Stub open connection so that DefaultDBManager.getConnection short-circuits
     * before attempting a real JDBC connection.
     */
    private static Connection openConnectionStub() {
        return (Connection) Proxy.newProxyInstance(InformixDBManagerTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("isClosed".equals(method.getName())) {
                        return false;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private ConnectInfo newConnectInfo(String url, String serviceName) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setUrl(url);
        connectInfo.setServiceName(serviceName);
        connectInfo.setConnection(openConnectionStub());
        return connectInfo;
    }

    @Test
    void appendsInformixServerPropertyOnceAcrossReconnects() {
        ConnectInfo connectInfo = newConnectInfo(BASE_URL, "ol_server");

        Connection first = dbManager.getConnection(connectInfo);
        assertSame(connectInfo.getConnection(), first);
        assertEquals(BASE_URL + ":INFORMIXSERVER=ol_server", connectInfo.getUrl());

        // simulate reconnect on the same cached ConnectInfo: URL must not grow
        Connection second = dbManager.getConnection(connectInfo);
        assertSame(connectInfo.getConnection(), second);
        assertEquals(BASE_URL + ":INFORMIXSERVER=ol_server", connectInfo.getUrl());
    }

    @Test
    void leavesUrlUntouchedWhenServiceNameBlank() {
        ConnectInfo connectInfo = newConnectInfo(BASE_URL, " ");

        dbManager.getConnection(connectInfo);

        assertEquals(BASE_URL, connectInfo.getUrl());
    }

    @Test
    void leavesUrlUntouchedWhenInformixServerAlreadyPresent() {
        String url = BASE_URL + ":INFORMIXSERVER=ol_server";
        ConnectInfo connectInfo = newConnectInfo(url, "other_server");

        dbManager.getConnection(connectInfo);

        assertEquals(url, connectInfo.getUrl());
    }
}
