package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.request.sql.DbSqlFormatRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbSqlServiceImplTest {

    private final DbSqlServiceImpl sqlService = new DbSqlServiceImpl();

    @Test
    void postgresqlFormatPlacesEveryInsertValueOnItsOwnLine() {
        String formatted = sqlService.format(request(
                "postgresql",
                "INSERT INTO demo (id, enabled, name) VALUES (0, FALSE, 'abc');"
        ));

        assertEquals("""
                INSERT INTO
                  demo (
                    id,
                    enabled,
                    name
                  )
                VALUES
                  (
                    0,
                    FALSE,
                    'abc'
                  );""", formatted);
    }

    @Test
    void mysqlFormatKeepsItsExistingDefaultColumnWidth() {
        String formatted = sqlService.format(request(
                "mysql",
                "INSERT INTO demo (id, enabled, name) VALUES (0, FALSE, 'abc');"
        ));

        assertEquals("""
                INSERT INTO
                  demo (id, enabled, name)
                VALUES
                  (0, FALSE, 'abc');""", formatted);
    }

    private static DbSqlFormatRequest request(String dbType, String sql) {
        return DbSqlFormatRequest.builder()
                .dbType(dbType)
                .sql(sql)
                .build();
    }
}
