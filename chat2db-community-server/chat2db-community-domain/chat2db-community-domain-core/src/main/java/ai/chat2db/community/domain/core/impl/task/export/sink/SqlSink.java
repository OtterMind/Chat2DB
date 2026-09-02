package ai.chat2db.community.domain.core.impl.task.export.sink;

import ai.chat2db.community.domain.api.model.task.pipeline.ExportSchema;
import ai.chat2db.spi.ISqlBuilder;
import ai.chat2db.spi.model.request.MultiInsertSqlRequest;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-value {@code INSERT} sink: rows accumulate until the statement reaches the row or byte
 * limit, then one statement is emitted. This replaces one {@code INSERT} per row, which meant one
 * statement (and one parser round-trip) per exported row.
 *
 * <p>Row values arrive as dialect-formatted SQL literals (the same strings the single-row path
 * passed to {@code buildInsert}); {@code escapeLineString} is applied by the builder for both.
 */
public final class SqlSink extends TextSink {

    static final int DEFAULT_MAX_ROWS_PER_STATEMENT = 800;

    static final int DEFAULT_MAX_BYTES_PER_STATEMENT = 1024 * 1024;

    private final ISqlBuilder sqlBuilder;

    private final String databaseName;

    private final String schemaName;

    private final int maxRowsPerStatement;

    private final int maxBytesPerStatement;

    private final List<List<String>> pendingRows = new ArrayList<>();

    private List<String> columnNames = List.of();

    private String tableName;

    private int pendingBytes;

    public SqlSink(OutputStream output, ISqlBuilder sqlBuilder, String databaseName, String schemaName) {
        this(output, sqlBuilder, databaseName, schemaName, DEFAULT_MAX_ROWS_PER_STATEMENT,
                DEFAULT_MAX_BYTES_PER_STATEMENT);
    }

    SqlSink(OutputStream output, ISqlBuilder sqlBuilder, String databaseName, String schemaName,
            int maxRowsPerStatement, int maxBytesPerStatement) {
        super(output);
        this.sqlBuilder = sqlBuilder;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.maxRowsPerStatement = Math.max(1, maxRowsPerStatement);
        this.maxBytesPerStatement = Math.max(1, maxBytesPerStatement);
    }

    @Override
    public void writeSchema(ExportSchema schema, String tableName) throws IOException {
        flush();
        this.columnNames = schema.getColumnNames();
        this.tableName = tableName;
    }

    @Override
    public void writeRows(List<List<Object>> batch) throws IOException {
        for (List<Object> row : batch) {
            List<String> values = new ArrayList<>(row.size());
            int rowBytes = 0;
            for (Object value : row) {
                String literal = value == null ? "NULL" : String.valueOf(value);
                values.add(literal);
                rowBytes += literal.length() + 2;
            }
            if (!pendingRows.isEmpty()
                    && (pendingRows.size() + 1 > maxRowsPerStatement
                    || pendingBytes + rowBytes > maxBytesPerStatement)) {
                flush();
            }
            pendingRows.add(values);
            pendingBytes += rowBytes;
            if (pendingRows.size() >= maxRowsPerStatement || pendingBytes >= maxBytesPerStatement) {
                flush();
            }
        }
    }

    @Override
    public void finishTable(String tableName) throws IOException {
        flush();
    }

    @Override
    public void close() throws IOException {
        flush();
        super.close();
    }

    private void flush() throws IOException {
        if (pendingRows.isEmpty()) {
            return;
        }
        write(sqlBuilder.dml().buildBatchInsert(MultiInsertSqlRequest.builder()
                .databaseName(StringUtils.trimToNull(databaseName))
                .schemaName(StringUtils.trimToNull(schemaName))
                .tableName(tableName)
                .columnList(List.copyOf(columnNames))
                .valueLists(List.copyOf(pendingRows))
                .build()));
        write(";\n");
        pendingRows.clear();
        pendingBytes = 0;
    }
}
