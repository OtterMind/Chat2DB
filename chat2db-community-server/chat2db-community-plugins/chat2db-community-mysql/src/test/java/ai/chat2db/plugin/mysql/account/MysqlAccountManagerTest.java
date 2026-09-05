package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.model.account.AccountInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlAccountManagerTest {

    @Test
    void listAccountsReadsAuthenticationPluginAndTlsMetadataFromMysqlUser() {
        MysqlAccountManager manager = new MysqlAccountManager();

        List<AccountInfo> accounts = manager.listAccounts(connectionForAccounts(List.of(Map.of(
                "User", "sec002_ssl",
                "Host", "%",
                "plugin", "caching_sha2_password",
                "account_locked", "N",
                "ssl_type", "SPECIFIED",
                "ssl_cipher", "AES256",
                "x509_issuer", "CN=issuer",
                "x509_subject", "CN=subject"
        ))));

        AccountInfo account = accounts.get(0);
        assertEquals("sec002_ssl", account.getUser());
        assertEquals("caching_sha2_password", account.getAuthenticationPlugin());
        assertEquals("SPECIFIED", account.getTlsRequirement());
        assertEquals("AES256", account.getTlsCipher());
        assertEquals("CN=issuer", account.getTlsIssuer());
        assertEquals("CN=subject", account.getTlsSubject());
    }

    private static Connection connectionForAccounts(List<Map<String, String>> rows) {
        return proxy(Connection.class, (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return statement(rows);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static PreparedStatement statement(List<Map<String, String>> rows) {
        return proxy(PreparedStatement.class, (proxy, method, args) -> {
            if ("executeQuery".equals(method.getName())) {
                return resultSet(rows);
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static ResultSet resultSet(List<Map<String, String>> rows) {
        int[] index = {-1};
        return proxy(ResultSet.class, (proxy, method, args) -> {
            if ("next".equals(method.getName())) {
                index[0] += 1;
                return index[0] < rows.size();
            }
            if ("getString".equals(method.getName())) {
                return rows.get(index[0]).get((String) args[0]);
            }
            return defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return 0;
    }
}
