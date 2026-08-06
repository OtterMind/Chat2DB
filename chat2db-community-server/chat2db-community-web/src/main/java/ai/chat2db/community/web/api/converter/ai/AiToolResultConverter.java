package ai.chat2db.community.web.api.converter.ai;

import ai.chat2db.community.domain.api.model.ai.TableSchemaResult;
import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.domain.api.model.metadata.SimpleTable;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.web.api.model.response.ai.AiDataSourceToolPayload;
import ai.chat2db.community.web.api.model.response.ai.AiDatabaseToolPayload;
import ai.chat2db.community.web.api.model.response.ai.AiSchemaToolPayload;
import ai.chat2db.community.web.api.model.response.ai.AiSqlResultSetPayload;
import ai.chat2db.community.web.api.model.response.ai.AiTableSchemaToolPayload;
import ai.chat2db.community.web.api.model.response.ai.AiTableToolPayload;
import ai.chat2db.community.web.api.model.response.ai.AiText2SqlToolPayload;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class AiToolResultConverter {

    private final AiSqlToolResultConverter sqlToolResultConverter;
    private final AiTableSchemaToolResultConverter tableSchemaToolResultConverter;

    public AiToolResultConverter() {
        this(new AiSqlToolResultConverter(), new AiTableSchemaToolResultConverter());
    }

    @Autowired
    public AiToolResultConverter(AiSqlToolResultConverter sqlToolResultConverter,
            AiTableSchemaToolResultConverter tableSchemaToolResultConverter) {
        this.sqlToolResultConverter = sqlToolResultConverter;
        this.tableSchemaToolResultConverter = tableSchemaToolResultConverter;
    }

    public AiToolOutput<List<AiDataSourceToolPayload>> fromDataSources(List<WorkspaceDataSource> dataSources) {
        List<AiDataSourceToolPayload> items = emptyIfNull(dataSources).stream()
                .filter(Objects::nonNull)
                .map(dataSource -> new AiDataSourceToolPayload(
                        dataSource.getId(),
                        StringUtils.defaultIfBlank(dataSource.getAlias(), "(unnamed)"),
                        dataSource.getType(),
                        dataSource.getEnvType()))
                .collect(Collectors.toList());
        String summary = items.isEmpty() ? "No datasources found." : "Found " + items.size() + " datasource(s).";
        return new AiToolOutput<>(summary, items);
    }

    public AiToolOutput<List<AiTableToolPayload>> fromTables(List<SimpleTable> tables) {
        List<AiTableToolPayload> items = emptyIfNull(tables).stream()
                .filter(Objects::nonNull)
                .map(table -> new AiTableToolPayload(
                        StringUtils.defaultString(table.getName(), "(unnamed)"),
                        StringUtils.defaultIfBlank(table.getTableType(), "TABLE"),
                        table.getComment()))
                .collect(Collectors.toList());
        String summary = items.isEmpty() ? "No tables found." : "Found " + items.size() + " table(s).";
        return new AiToolOutput<>(summary, items);
    }

    public AiToolOutput<List<AiDatabaseToolPayload>> fromDatabases(List<Database> databases) {
        List<AiDatabaseToolPayload> items = emptyIfNull(databases).stream()
                .filter(Objects::nonNull)
                .map(database -> new AiDatabaseToolPayload(
                        StringUtils.defaultString(database.getName(), "(unnamed)"),
                        database.isSystem(),
                        database.getComment()))
                .collect(Collectors.toList());
        String summary = items.isEmpty() ? "No databases found." : "Found " + items.size() + " database(s).";
        return new AiToolOutput<>(summary, items);
    }

    public AiToolOutput<List<AiSchemaToolPayload>> fromSchemas(List<Schema> schemas) {
        List<AiSchemaToolPayload> items = emptyIfNull(schemas).stream()
                .filter(Objects::nonNull)
                .map(schema -> new AiSchemaToolPayload(
                        StringUtils.defaultString(schema.getName(), "(unnamed)"),
                        schema.isSystem(),
                        schema.getComment()))
                .collect(Collectors.toList());
        String summary = items.isEmpty() ? "No schemas found." : "Found " + items.size() + " schema(s).";
        return new AiToolOutput<>(summary, items);
    }

    public AiToolOutput<List<AiSqlResultSetPayload>> fromExecuteResult(List<ExecuteResponse> executeResponses) {
        return sqlToolResultConverter.fromExecuteResult(executeResponses);
    }

    public AiToolOutput<List<AiTableSchemaToolPayload>> fromTableSchemas(List<TableSchemaResult> schemaResults) {
        return tableSchemaToolResultConverter.fromTableSchemas(schemaResults);
    }

    public AiToolOutput<List<AiText2SqlToolPayload>> fromText2Sql(String sql) {
        return new AiToolOutput<>(
                "SQL generated successfully.",
                List.of(new AiText2SqlToolPayload(StringUtils.defaultString(sql))));
    }

    private <T> List<T> emptyIfNull(List<T> items) {
        return items == null ? Collections.emptyList() : items;
    }
}
