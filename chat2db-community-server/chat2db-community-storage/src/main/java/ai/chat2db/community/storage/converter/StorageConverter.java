package ai.chat2db.community.storage.converter;

import ai.chat2db.community.domain.api.model.datasource.DataSource;
import ai.chat2db.community.domain.api.model.datasource.DataSourceNamespace;
import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.Type;
import ai.chat2db.community.domain.api.model.operation.Operation;
import ai.chat2db.community.domain.api.model.pin.PinTable;
import ai.chat2db.community.domain.api.model.request.operation.OpsOperationPageQueryRequest;
import ai.chat2db.community.domain.api.model.request.pin.DbTablePinRequest;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSource;
import ai.chat2db.community.domain.api.model.storage.WorkspaceDataSourceNamespace;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public abstract class StorageConverter {

    public abstract WorkspaceDataSource dataSource2workspace(DataSource dataSource);

    public abstract DataSource workspace2dataSource(WorkspaceDataSource dataSource);

    public abstract List<WorkspaceDataSource> dataSource2workspace(List<DataSource> dataSources);

    public abstract WorkspaceDataSourceNamespace dataSourceNamespace2workspace(DataSourceNamespace namespace);

    public abstract PinTable pinTableParam2model(DbTablePinRequest param);

    public abstract Operation operationPageParam2model(OpsOperationPageQueryRequest param);

    public abstract ColumnType type2columnType(Type type);

}
