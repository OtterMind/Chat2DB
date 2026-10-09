package ai.chat2db.community.domain.core.converter;

import ai.chat2db.community.domain.api.model.request.db.DbDlExecuteRequest;
import ai.chat2db.community.domain.api.model.request.db.DbCopyInValuesRequest;
import ai.chat2db.community.domain.api.model.request.db.DbSelectResultUpdateRequest;
import ai.chat2db.community.domain.api.service.db.IDbSqlCommandService;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.result.QueryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

@Mapper(componentModel = "spring")
public abstract class CommandConverter implements IDbSqlCommandService {

    @Mappings({
            @Mapping(target = "script", source = "sql"),
            // Only normalised parameters reach the executor; see SqlParameterRequests.
            @Mapping(target = "parameters", ignore = true),
            @Mapping(target = "positionalParameterStyle", ignore = true)
    })
    public abstract SqlExecuteRequest param2model(DbDlExecuteRequest param);

    @Override
    public SqlExecuteRequest toSqlExecuteRequest(DbDlExecuteRequest param) {
        return param2model(param);
    }

    public abstract QueryResponse updateSelectResult2query(DbSelectResultUpdateRequest param);

    public abstract QueryResponse copyInValues2query(DbCopyInValuesRequest param);
}
