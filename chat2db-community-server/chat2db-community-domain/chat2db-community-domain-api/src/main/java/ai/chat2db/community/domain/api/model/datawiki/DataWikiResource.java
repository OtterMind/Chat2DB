package ai.chat2db.community.domain.api.model.datawiki;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataWikiResource {

    private String id;

    private Long dataSourceId;

    private String dataSourceName;

    private String databaseName;

    private String schemaName;

    private String tableName;

    private String tableType;

    private String sourceComment;

    private String businessName;

    private String businessDescription;

    private List<DataWikiColumn> columns = new ArrayList<>();
}
