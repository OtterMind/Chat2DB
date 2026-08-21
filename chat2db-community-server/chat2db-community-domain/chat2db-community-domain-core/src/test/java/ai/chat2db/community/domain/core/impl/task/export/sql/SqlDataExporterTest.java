package ai.chat2db.community.domain.core.impl.task.export.sql;

import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlDataExporterTest {

    @Test
    void createsUtf8Writer(@TempDir Path tempDirectory) throws IOException {
        SqlDataExporter exporter = new SqlDataExporter();
        Path output = tempDirectory.resolve("data.sql");
        String content = "INSERT INTO test VALUES ('\u6570\u636e', '\u00e9');";

        try (BufferedWriter writer = exporter.createWriter(output.toFile())) {
            writer.write(content);
        }

        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output));
    }

    @Test
    void writerIOExceptionBecomesFileWriteFailure() {
        SqlDataExporter exporter = new SqlDataExporter();
        IOException failure = new IOException("Disk write failed");
        BufferedWriter writer = new BufferedWriter(new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                throw failure;
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        List<String> sql = new ArrayList<>(List.of("x".repeat(9000)));

        TaskExecutionException exception = assertThrows(TaskExecutionException.class,
                () -> exporter.writeSqlList(writer, sql));

        assertEquals(TaskErrorCode.FILE_WRITE_FAILED.name(), exception.getCode());
        assertInstanceOf(IOException.class, exception.getCause());
    }
}
