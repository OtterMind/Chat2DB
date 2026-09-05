package ai.chat2db.plugin.mariadb;

import ai.chat2db.community.domain.api.model.metadata.CheckConstraintInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MariaDBMetaDataTest {

    @Test
    void checkConstraintsDoesNotRunMysqlMetadataSql() {
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    throw new AssertionError("MariaDB CHECK metadata must not access the MySQL implementation");
                });

        List<CheckConstraintInfo> constraints = new MariaDBMetaData().checkConstraints(connection,
                new TableMetadataRequest("test_db", null, "test_table"));

        assertEquals(List.of(), constraints);
    }
}
