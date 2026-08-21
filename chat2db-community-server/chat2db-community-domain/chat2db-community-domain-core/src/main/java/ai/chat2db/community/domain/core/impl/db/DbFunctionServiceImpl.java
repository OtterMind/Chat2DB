package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbFunctionService;
import ai.chat2db.community.domain.core.util.ListSorter;
import ai.chat2db.community.domain.api.model.metadata.Function;
import ai.chat2db.spi.model.request.FunctionMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DbFunctionServiceImpl implements IDbFunctionService {
    @Override
    public List<Function> functions(String databaseName, String schemaName) {
        List<Function> functions = Chat2DBContext.getDbMetaData().functions(Chat2DBContext.getConnection(), databaseName, schemaName);
        ListSorter.sortByKey(functions, Function::getFunctionName);
        return functions;
    }

    @Override
    public Function detail(String databaseName, String schemaName, String functionName) {
        return Chat2DBContext.getDbMetaData().function(Chat2DBContext.getConnection(),
                new FunctionMetadataRequest(databaseName, schemaName, functionName));
    }

    @Override
    public void drop(String databaseName, String schemaName, String functionName) {
        Chat2DBContext.getDbManager().dropFunction(Chat2DBContext.getConnection(),
                databaseName, schemaName, functionName);
    }
}
