package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.request.sql.DbSqlFormatRequest;
import com.github.vertical_blank.sqlformatter.SqlFormatter;
import com.github.vertical_blank.sqlformatter.languages.Dialect;
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

    @Test
    void postgresqlFormatKeepsDefaultLayoutForNonInsertSql() {
        String sql = "SELECT COALESCE(name, email, 'unknown') FROM demo WHERE id IN (1, 2, 3);";

        String formatted = sqlService.format(request("postgresql", sql));

        assertEquals(SqlFormatter.of(Dialect.PostgreSql).format(sql), formatted);
    }

    @Test
    void postgresqlFormatKeepsDefaultLayoutForMixedStatementScripts() {
        String sql = """
                INSERT INTO demo (id, name) VALUES (1, 'one');
                SELECT COALESCE(name, 'unknown') FROM demo;""";

        String formatted = sqlService.format(request("postgresql", sql));

        assertEquals(SqlFormatter.of(Dialect.PostgreSql).format(sql), formatted);
    }

    private static DbSqlFormatRequest request(String dbType, String sql) {
        return DbSqlFormatRequest.builder()
                .dbType(dbType)
                .sql(sql)
                .build();
    }
}
