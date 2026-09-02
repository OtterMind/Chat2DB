package ai.chat2db.community.domain.core.impl.task.export.sink;

import ai.chat2db.community.domain.api.model.task.pipeline.ExportSchema;
import ai.chat2db.spi.DefaultSqlBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSinkTest {

    private static List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static String export(int maxRows, int maxBytes, List<List<Object>>... batches) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SqlSink sink = new SqlSink(out, new DefaultSqlBuilder(), null, null, maxRows, maxBytes);
        sink.writeSchema(new ExportSchema(List.of("id", "name")), "orders");
        for (List<List<Object>> batch : batches) {
            sink.writeRows(batch);
        }
        sink.finishTable("orders");
        sink.close();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void rowsAccumulateIntoOneMultiValueStatement() throws IOException {
        String sql = export(800, 1024 * 1024,
                List.of(row("1", "'a'"), row("2", "'b'")),
                List.of(row("3", null)));

        assertEquals(1, sql.split(";\n").length);
        assertTrue(sql.startsWith("INSERT INTO orders (id,name)  VALUES "), sql);
        assertTrue(sql.contains("(1,'a')"), sql);
        assertTrue(sql.contains("(2,'b')"), sql);
        assertTrue(sql.contains("(3,NULL)"), sql);
        assertTrue(sql.endsWith(";\n"), sql);
    }

    @Test
    void statementsAreSplitByRowCount() throws IOException {
        String sql = export(3, 1024 * 1024, List.of(
                row("1", "'a'"), row("2", "'b'"), row("3", "'c'"),
                row("4", "'d'"), row("5", "'e'")));

        String[] statements = sql.split(";\n");
        assertEquals(2, statements.length);
        assertTrue(statements[0].contains("(3,'c')"), statements[0]);
        assertTrue(statements[1].startsWith("INSERT INTO orders (id,name)  VALUES (4,'d')"), statements[1]);
    }

    @Test
    void statementsAreSplitBeforeOvershootingTheByteLimit() throws IOException {
        String bigValue = "x".repeat(40);
        String sql = export(800, 100, List.of(
                row("1", "'" + bigValue + "'"), row("2", "'" + bigValue + "'"),
                row("3", "'" + bigValue + "'")));

        // Rows cost ~47 bytes each: statements hold at most two rows before the 100-byte cap.
        assertEquals(2, sql.split(";\n").length);
    }
}
