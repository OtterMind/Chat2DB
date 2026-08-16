package ai.chat2db.community.domain.core.impl.db;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbDlTemplateServiceImplTest {

    @Test
    void mongodbCountDelegatesTheOriginalCollectionNameToTheDialectExecutor() {
        AtomicReference<SqlExecuteRequest> capturedRequest = new AtomicReference<>();
        DefaultSQLExecutor executor = new DefaultSQLExecutor() {
            @Override
            public List<ExecuteResponse> executeSelectTable(SqlExecuteRequest request) {
                capturedRequest.set(request);
                ExecuteResponse response = new ExecuteResponse();
                response.setDataList(List.of(List.of(), List.of()));
                return List.of(response);
            }
        };

        String collectionName = "my-field.1\"); db.dropDatabase(); //";
        assertEquals(2L, DbDlTemplateServiceImpl.getCountOfMongodb(collectionName, executor));
        assertEquals(collectionName, capturedRequest.get().getTableName());
    }
}
