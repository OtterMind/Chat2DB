package ai.chat2db.community.domain.api.model.metadata;

import java.io.Serializable;
import java.util.List;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TableIndex implements Serializable {
    private static final long serialVersionUID = 1L;

    private String oldName;




    private String name;




    private String tableName;




    private String type;




    private Boolean unique;




    private String comment;




    private String schemaName;




    private String databaseName;




    private List<TableIndexColumn> columnList;


    private String editStatus;




    private Boolean concurrently;




    private String method;

    private Boolean visible;


    private String foreignSchemaName;




    private String foreignTableName;




    private List<String> foreignColumnNamelist;

}
