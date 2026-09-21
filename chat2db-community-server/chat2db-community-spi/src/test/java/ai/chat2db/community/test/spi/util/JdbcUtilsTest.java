package ai.chat2db.community.test.spi.util;

import ai.chat2db.spi.util.JdbcUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcUtilsTest {

    @Test
    void replaceUrlHostAndPortForSshRewritesSqlServerSemicolonAuthority() {
        String url = JdbcUtils.replaceUrlHostAndPortForSsh(
                "jdbc:sqlserver://sqlserver.example.com:1433;databaseName=demo;encrypt=true",
                "sqlserver.example.com",
                "1433",
                "49152"
        );

        assertEquals("jdbc:sqlserver://127.0.0.1:49152;databaseName=demo;encrypt=true", url);
    }

    @Test
    void replaceUrlHostAndPortForSshPreservesMysqlPathAndQuery() {
        String url = JdbcUtils.replaceUrlHostAndPortForSsh(
                "jdbc:mysql://mysql.example.com:3306/demo?useSSL=false&serverName=mysql.example.com:3306",
                "mysql.example.com",
                "3306",
                "49152"
        );

        assertEquals("jdbc:mysql://127.0.0.1:49152/demo?useSSL=false&serverName=mysql.example.com:3306", url);
    }

    @Test
    void replaceUrlHostAndPortForSshPreservesPostgresPathAndQuery() {
        String url = JdbcUtils.replaceUrlHostAndPortForSsh(
                "jdbc:postgresql://postgres.example.com:5432/demo?sslmode=require",
                "postgres.example.com",
                "5432",
                "49152"
        );

        assertEquals("jdbc:postgresql://127.0.0.1:49152/demo?sslmode=require", url);
    }

    @Test
    void replaceUrlHostAndPortForSshRewritesBracketedIpv6Host() {
        String url = JdbcUtils.replaceUrlHostAndPortForSsh(
                "jdbc:mysql://[2001:db8::1002]:13306/demo?targetServerType=primary",
                "2001:db8::1002",
                "13306",
                "49152"
        );

        assertEquals("jdbc:mysql://127.0.0.1:49152/demo?targetServerType=primary", url);
    }

    @Test
    void replaceUrlHostAndPortForSshRewritesAlreadyBracketedHostValue() {
        String url = JdbcUtils.replaceUrlHostAndPortForSsh(
                "jdbc:mysql://[2001:db8::1002]:13306/",
                "[2001:db8::1002]",
                "13306",
                "49152"
        );

        assertEquals("jdbc:mysql://127.0.0.1:49152/", url);
    }

    @Test
    void replaceUrlHostAndPortForSshKeepsIpv4AndHostnameBehavior() {
        String url = JdbcUtils.replaceUrlHostAndPortForSsh(
                "jdbc:mysql://mysql.example.com:3306/demo",
                "mysql.example.com",
                "3306",
                "49152"
        );

        assertEquals("jdbc:mysql://127.0.0.1:49152/demo", url);
    }

    @Test
    void replaceUrlHostAndPortForSshDoesNotRewriteHostSubstringOutsideAuthority() {
        String url = JdbcUtils.replaceUrlHostAndPortForSsh(
                "jdbc:mysql://prod-mysql.example.com:3306/mysql.example.com_demo?serverName=mysql.example.com:3306",
                "mysql.example.com",
                "3306",
                "49152"
        );

        assertEquals(
                "jdbc:mysql://prod-mysql.example.com:3306/mysql.example.com_demo?serverName=mysql.example.com:3306",
                url
        );
    }

    @Test
    void replaceUrlHostAndPortForSshOnlyRewritesAuthorityWhenHostAlsoAppearsInPathAndQuery() {
        String url = JdbcUtils.replaceUrlHostAndPortForSsh(
                "jdbc:mysql://mysql.example.com:3306/mysql.example.com_demo?serverName=mysql.example.com:3306",
                "mysql.example.com",
                "3306",
                "49152"
        );

        assertEquals(
                "jdbc:mysql://127.0.0.1:49152/mysql.example.com_demo?serverName=mysql.example.com:3306",
                url
        );
    }
}
