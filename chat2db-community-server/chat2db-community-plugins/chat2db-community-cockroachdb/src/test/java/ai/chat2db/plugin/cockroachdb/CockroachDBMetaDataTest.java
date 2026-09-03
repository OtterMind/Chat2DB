package ai.chat2db.plugin.cockroachdb;

import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.plugin.cockroachdb.builder.CockroachDBSqlBuilder;
import ai.chat2db.spi.ISqlBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CockroachDBMetaDataTest {

    @Test
    void createDatabaseUsesCockroachBareSyntax() {
        ISqlBuilder builder = new CockroachDBMetaData().getSqlBuilder();
        Database database = new Database();
        database.setName("analytics");
        database.setCharset("UTF8");
        database.setCollation("en_US.UTF-8");
        database.setComment("reporting database");

        String sql = builder.ddl().database().buildCreateDatabase(database);

        assertEquals("CREATE DATABASE \"analytics\"", sql);
        assertFalse(sql.contains("WITH"), sql);
        assertFalse(sql.contains("LC_CTYPE"), sql);
        assertFalse(sql.contains("LC_COLLATE"), sql);
    }

    @Test
    void metadataWiresCockroachSqlBuilder() {
        assertInstanceOf(CockroachDBSqlBuilder.class, new CockroachDBMetaData().getSqlBuilder());
    }
}
