package ai.chat2db.plugin.generic;

import ai.chat2db.plugin.duckdb.DuckDBSyntaxPlugin;
import ai.chat2db.plugin.elasticsearch.ElasticSearchSyntaxPlugin;
import ai.chat2db.plugin.tdengine.TDengineSyntaxPlugin;
import ai.chat2db.spi.ISqlSyntaxPlugin;
import ai.chat2db.community.domain.api.config.DBConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericPluginSyntaxRoutingTest {

    private ISqlSyntaxPlugin resolve(String dbType, String sqlDialect) {
        DBConfig config = new DBConfig();
        config.setDbType(dbType);
        config.setSqlDialect(sqlDialect);
        return new GenericPlugin().getPlugin(config).getSqlSyntaxPlugin();
    }

    @Test
    void resolvesBundledDialectsByDbTypeWithoutHardcodedBranches() {
        assertTrue(resolve("DUCKDB", null) instanceof DuckDBSyntaxPlugin);
        assertTrue(resolve("ELASTICSEARCH", null) instanceof ElasticSearchSyntaxPlugin);
        assertTrue(resolve("TDENGINE", null) instanceof TDengineSyntaxPlugin);
    }

    @Test
    void fallsBackToConfiguredSqlDialectForUnknownDbType() {
        assertTrue(resolve("SOME_NEW_DB", "DUCKDB") instanceof DuckDBSyntaxPlugin);
        assertTrue(resolve("QUESTDB", "tdengine") instanceof TDengineSyntaxPlugin);
    }

    @Test
    void returnsNullWhenNeitherDbTypeNorDialectIsRegistered() {
        assertNull(resolve("SOME_NEW_DB", null));
        assertNull(resolve("SOME_NEW_DB", "NO_SUCH_DIALECT"));
    }

    @Test
    void returnsNullWithoutConfig() {
        assertNull(new GenericPlugin().getSqlSyntaxPlugin());
    }
}
