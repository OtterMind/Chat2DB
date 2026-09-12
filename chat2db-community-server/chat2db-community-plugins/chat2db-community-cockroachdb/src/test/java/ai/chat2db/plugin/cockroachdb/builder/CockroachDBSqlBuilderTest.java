package ai.chat2db.plugin.cockroachdb.builder;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import ai.chat2db.community.domain.api.model.result.ResultOperation;
import ai.chat2db.plugin.cockroachdb.CockroachDBMetaData;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.constant.SQLConstants;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CockroachDBSqlBuilderTest {

    private static final String DB_TYPE = "COCKROACHDB_BUILDER_TEST";

    private IPlugin previousPlugin;

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DB_TYPE);
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, previousPlugin);
        }
    }

    @Test
    void singleRowGuardUsesRowidForTablesWithoutPrimaryKey() {
        bindCockroachMetadata();
        CockroachDBSqlBuilder builder = new CockroachDBSqlBuilder();

        String sql = builder.buildByQueryResult(queryResponse(SQLConstants.DELETE_KEYWORD));

        assertEquals("DELETE FROM \"orders\" where rowid in (select rowid from \"orders\""
                + " where \"id\" = '7' and \"name\" = 'Ada'  limit 1);\n", sql);
        assertFalse(sql.contains("ctid"));
    }

    @Test
    void metadataWiresCockroachSqlBuilder() {
        assertInstanceOf(CockroachDBSqlBuilder.class, new CockroachDBMetaData().getSqlBuilder());
    }

    private void bindCockroachMetadata() {
        DBConfig config = new DBConfig();
        config.setDbType(DB_TYPE);
        IDbMetaData metadata = new CockroachDBMetaData();
        previousPlugin = Chat2DBContext.PLUGIN_MAP.put(DB_TYPE, new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return metadata;
            }
        });
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(DB_TYPE);
        Chat2DBContext.putContext(connectInfo);
    }

    private static QueryResponse queryResponse(String operationType) {
        QueryResponse response = new QueryResponse();
        response.setTableName("\"orders\"");
        response.setHeaderList(List.of(
                Header.builder().name("_selector").primaryKey(false).columnType("text").build(),
                Header.builder().name("id").primaryKey(false).columnType("int4").build(),
                Header.builder().name("name").primaryKey(false).columnType("varchar").build()));
        ResultOperation operation = new ResultOperation();
        operation.setType(operationType);
        operation.setOldDataList(List.of("", "7", "Ada"));
        response.setOperations(List.of(operation));
        return response;
    }
}
