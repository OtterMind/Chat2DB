package ai.chat2db.community.domain.core.impl.db;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbDlTemplateServiceImplTest {

    @Test
    void mongodbCountRequiresACollectionName() {
        DefaultSQLExecutor executor = new DefaultSQLExecutor() {
            @Override
            public List<ExecuteResponse> executeSelectTable(SqlExecuteRequest request) {
                throw new AssertionError("the executor must not be called without a collection name");
            }
        };

        BusinessException missing = assertThrows(BusinessException.class,
                () -> DbDlTemplateServiceImpl.getCountOfMongodb(null, executor));
        assertEquals("count.mongodbTableRequired", missing.getCode());

        BusinessException blank = assertThrows(BusinessException.class,
                () -> DbDlTemplateServiceImpl.getCountOfMongodb("   ", executor));
        assertEquals("count.mongodbTableRequired", blank.getCode());
    }

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
