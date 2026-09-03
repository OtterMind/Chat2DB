package ai.chat2db.plugin.mysql.variable;

import ai.chat2db.community.domain.api.service.db.IDbVariableService.EditMeta;
import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MysqlVariableManagerTest {

    @Test
    void sessionOnlyVariableNeverAdvertisesOrAcceptsPersistScopes() {
        TestableMysqlVariableManager manager = manager(List.of(), Map.of());

        EditMeta metadata = manager.editable(null, "5.7.44", "autocommit");

        assertEquals(List.of("SESSION"), metadata.dynamicScopes());
        assertEquals(List.of(), metadata.persistScopes());
        assertThrows(BusinessException.class,
                () -> manager.previewSetVariableSql(null, "8.0.36", "autocommit", "1", "PERSIST"));
    }

    @Test
    void persistScopesRequireMysqlEightAndVariableCapability() {
        assertEquals(List.of(), MysqlVariableManager.persistScopes(MysqlEditableVariable.SQL_MODE, false));
        assertEquals(List.of("PERSIST", "PERSIST_ONLY"),
                MysqlVariableManager.persistScopes(MysqlEditableVariable.SQL_MODE, true));

        TestableMysqlVariableManager manager = manager(List.of(), Map.of());
        assertThrows(BusinessException.class,
                () -> manager.previewSetVariableSql(null, "5.7.44", "sql_mode", "STRICT_TRANS_TABLES", "PERSIST"));
    }

    @Test
    void mysqlEightVariablesIncludePerformanceSchemaSourceAndPath() {
        TestableMysqlVariableManager manager = manager(
                List.of(row("sql_mode", "STRICT_TRANS_TABLES")),
                Map.of("sql_mode", new MysqlVariableManager.VariableInfo(
                        "sql_mode", "EXPLICIT", "/etc/my.cnf", null, null, null, null, null)));

        List<Map<String, Object>> variables = manager.variables(null, "8.0.36", "GLOBAL", "VARIABLES");

        assertEquals("sql_mode", variables.get(0).get("name"));
        assertEquals("STRICT_TRANS_TABLES", variables.get(0).get("value"));
        assertEquals("EXPLICIT", variables.get(0).get("source"));
        assertEquals("/etc/my.cnf", variables.get(0).get("path"));
        assertEquals("SHOW GLOBAL VARIABLES", manager.lastSql);
    }

    @Test
    void mysqlFiveSevenDegradesWithoutInventingMetadata() {
        TestableMysqlVariableManager manager = manager(
                List.of(row("sql_mode", "STRICT_TRANS_TABLES")),
                Map.of("sql_mode", new MysqlVariableManager.VariableInfo(
                        "sql_mode", "EXPLICIT", "/ignored", null, null, null, null, null)));

        List<Map<String, Object>> variables = manager.variables(null, "5.7.44", "GLOBAL", "VARIABLES");
        EditMeta metadata = manager.editable(null, "5.7.44", "sql_mode");

        assertFalse(variables.get(0).containsKey("source"));
        assertFalse(variables.get(0).containsKey("path"));
        assertNull(metadata.source());
        assertNull(metadata.path());
        assertEquals(List.of(), metadata.persistScopes());
    }

    @Test
    void mysqlEightDoesNotAllowWritesWhenMetadataDoesNotExposeVariable() {
        TestableMysqlVariableManager manager = manager(List.of(), Map.of());

        assertNull(manager.editable(null, "8.0.36", "sql_mode"));
        assertThrows(BusinessException.class,
                () -> manager.previewSetVariableSql(
                        null, "8.0.36", "sql_mode", "STRICT_TRANS_TABLES", "GLOBAL"));
    }

    @Test
    void registeredVariableBuildsOnlyItsSupportedSql() {
        TestableMysqlVariableManager manager = manager(
                List.of(),
                Map.of("sql_mode", new MysqlVariableManager.VariableInfo(
                        "sql_mode", "DYNAMIC", null, null, null, null, null, null)));

        EditMeta metadata = manager.editable(null, "8.0.36", "sql_mode");

        assertEquals(List.of("SESSION", "GLOBAL"), metadata.dynamicScopes());
        assertEquals(List.of("PERSIST", "PERSIST_ONLY"), metadata.persistScopes());
        assertEquals("SET GLOBAL sql_mode = 'STRICT_TRANS_TABLES'",
                manager.previewSetVariableSql(
                        null, "8.0.36", "sql_mode", "STRICT_TRANS_TABLES", "GLOBAL"));
        assertEquals("SET SESSION autocommit = OFF",
                manager(null, Map.of()).previewSetVariableSql(
                        null, "5.7.44", "autocommit", "0", "SESSION"));
    }

    @Test
    void showQueriesStayInsideMysqlManager() {
        TestableMysqlVariableManager manager = manager(List.of(), Map.of());

        manager.variables(null, "5.7.44", "GLOBAL", "STATUS");
        assertEquals("SHOW GLOBAL STATUS", manager.lastSql);
        manager.variables(null, "5.7.44", "SESSION", "STATUS");
        assertEquals("SHOW SESSION STATUS", manager.lastSql);
        manager.variables(null, "5.7.44", "SESSION", "VARIABLES");
        assertEquals("SHOW SESSION VARIABLES", manager.lastSql);
    }

    private static TestableMysqlVariableManager manager(List<Map<String, Object>> rows,
                                                        Map<String, MysqlVariableManager.VariableInfo> variableInfo) {
        return new TestableMysqlVariableManager(rows, variableInfo);
    }

    private static Map<String, Object> row(String name, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("value", value);
        return row;
    }

    private static final class TestableMysqlVariableManager extends MysqlVariableManager {

        private final List<Map<String, Object>> rows;
        private final Map<String, VariableInfo> variableInfo;
        private String lastSql;

        private TestableMysqlVariableManager(List<Map<String, Object>> rows,
                                             Map<String, VariableInfo> variableInfo) {
            this.rows = rows;
            this.variableInfo = variableInfo;
        }

        @Override
        protected List<Map<String, Object>> queryNameValueRows(Connection connection, String sql) {
            lastSql = sql;
            return rows;
        }

        @Override
        protected Map<String, VariableInfo> queryVariableInfo(Connection connection) {
            return variableInfo;
        }
    }
}
