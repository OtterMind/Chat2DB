package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.SqlParameterValue;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.util.SqlParameterParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParameterRequestsTest {

    @AfterEach
    void clearContext() {
        Chat2DBContext.removeContext();
    }

    @Test
    void switchOffLeavesSqlUntouchedWithoutScanning() {
        useDatabase("MYSQL");

        assertNull(SqlParameterRequests.normalize("SELECT :id", null, null, null));
        assertNull(SqlParameterRequests.normalize("SELECT :id", false, Map.of(), List.of()));
    }

    @Test
    void switchOnRejectsPlaceholdersWithoutValuesBeforeAnythingRuns() {
        useDatabase("MYSQL");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> SqlParameterRequests.normalize("SELECT 1; SELECT * FROM t WHERE id = ?", true, null, null));

        assertEquals("sqlParameter.required", exception.getCode());
        assertNull(SqlParameterRequests.normalize("SELECT ':id', '?' -- :x", true, null, null));
    }

    @Test
    void suppliedValuesAreBoundWhateverTheSwitchSays() {
        useDatabase("MYSQL");

        SqlParameterParser.NormalizedSql normalized = SqlParameterRequests.normalize(
                "SELECT * FROM t WHERE id = ?", false, null, List.of(SqlParameterValue.number("7")));
        SqlExecuteRequest command = new SqlExecuteRequest();
        SqlParameterRequests.apply(command, normalized);

        assertEquals("SELECT * FROM t WHERE id = :__p1", normalized.sql());
        assertEquals(Map.of("__p1", SqlParameterValue.number("7")), command.getParameters());
        assertTrue(command.isPositionalParameterStyle());
    }

    @Test
    void databasesWithoutSqlTextRejectValuesButIgnoreTheSwitch() {
        useDatabase("MONGODB");

        BusinessException exception = assertThrows(BusinessException.class, () -> SqlParameterRequests.normalize(
                "db.users.find({id: ?})", true, null, List.of(SqlParameterValue.number("7"))));

        assertEquals("sqlParameter.unsupportedDatabase", exception.getCode());
        assertNull(SqlParameterRequests.normalize("db.users.find({a: '?'})", true, null, null));
    }

    private static void useDatabase(String type) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(type);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType(type);
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);
    }
}
