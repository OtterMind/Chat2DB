package ai.chat2db.community.domain.api.model.task.pipeline;

import java.util.Collections;
import java.util.List;

/**
 * Column names of one exported table, handed to a {@link FormatSink} before its first data row.
 */
public final class ExportSchema {

    private final List<String> columnNames;

    public ExportSchema(List<String> columnNames) {
        this.columnNames = columnNames == null ? Collections.emptyList() : List.copyOf(columnNames);
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public int getColumnCount() {
        return columnNames.size();
    }
}
