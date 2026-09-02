package ai.chat2db.community.domain.core.impl.task.export.sink;

import ai.chat2db.community.domain.api.model.task.pipeline.ExportSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared row-object rendering for the JSON-shaped sinks.
 */
abstract class JsonObjectSink extends TextSink {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    protected List<String> columnNames = List.of();

    JsonObjectSink(OutputStream output) {
        super(output);
    }

    @Override
    public void writeSchema(ExportSchema schema, String tableName) throws IOException {
        this.columnNames = schema.getColumnNames();
    }

    protected final String objectOf(List<Object> row) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>(columnNames.size());
        for (int index = 0; index < columnNames.size(); index++) {
            values.put(columnNames.get(index), index < row.size() ? row.get(index) : null);
        }
        return MAPPER.writeValueAsString(values);
    }
}
