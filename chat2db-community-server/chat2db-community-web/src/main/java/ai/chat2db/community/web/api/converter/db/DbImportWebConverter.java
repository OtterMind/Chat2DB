package ai.chat2db.community.web.api.converter.db;

import ai.chat2db.community.domain.api.model.db.MappedImportExecution;
import ai.chat2db.community.web.api.model.request.db.ImportExecuteRequest;
import org.springframework.stereotype.Component;

@Component
public class DbImportWebConverter {

    public MappedImportExecution toMappedImportExecution(ImportExecuteRequest request) {
        return MappedImportExecution.builder()
                .dataSourceId(request.getDataSourceId())
                .databaseName(request.getDatabaseName())
                .schemaName(request.getSchemaName())
                .tableName(request.getTableName())
                .fileId(request.getFileId())
                .csvOptions(request.getCsvOptions())
                .mappings(request.getMappings())
                .unmappedTarget(request.getUnmappedTarget())
                .build();
    }
}
