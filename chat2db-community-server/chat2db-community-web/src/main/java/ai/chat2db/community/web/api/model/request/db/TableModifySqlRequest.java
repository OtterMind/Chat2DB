package ai.chat2db.community.web.api.model.request.db;

import ai.chat2db.community.domain.api.model.metadata.Table;
import jakarta.validation.constraints.NotNull;

import ai.chat2db.community.web.api.model.request.data.source.DataSourceBaseRequest;

import lombok.Data;


@Data
public class TableModifySqlRequest extends DataSourceBaseRequest {


    private Table oldTable;


    @NotNull
    private Table newTable;

    private Boolean allowGeneratedColumnStorageRebuild;
}
