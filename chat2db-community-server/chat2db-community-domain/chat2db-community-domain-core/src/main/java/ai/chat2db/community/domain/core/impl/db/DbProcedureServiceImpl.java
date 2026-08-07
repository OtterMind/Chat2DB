package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbProcedureService;
import ai.chat2db.community.domain.core.util.ListSorter;
import ai.chat2db.community.domain.api.model.metadata.Procedure;
import ai.chat2db.spi.model.request.ProcedureMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DbProcedureServiceImpl implements IDbProcedureService {

    @Override
    public List<Procedure> procedures(String databaseName, String schemaName) {
        List<Procedure> procedures = Chat2DBContext.getDbMetaData().procedures(Chat2DBContext.getConnection(), databaseName, schemaName);
        ListSorter.sortByKey(procedures, Procedure::getProcedureName);
        return procedures;
    }

    @Override
    public Procedure detail(String databaseName, String schemaName, String procedureName) {
        return Chat2DBContext.getDbMetaData().procedure(Chat2DBContext.getConnection(),
                new ProcedureMetadataRequest(databaseName, schemaName, procedureName));
    }

    @Override
    public void drop(String databaseName, String schemaName, String procedureName) {
        Chat2DBContext.getDbManager().dropProcedure(Chat2DBContext.getConnection(),
                databaseName, schemaName, procedureName);
    }
}
