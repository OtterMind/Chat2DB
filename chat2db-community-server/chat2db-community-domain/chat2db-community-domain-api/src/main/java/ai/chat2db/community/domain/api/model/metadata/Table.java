package ai.chat2db.community.domain.api.model.metadata;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Table implements Serializable {
    private static final long serialVersionUID = 1L;




    @JsonAlias({"TABLE_NAME"})
    private String name;




    @JsonAlias({"REMARKS"})

    private String comment;




    @JsonAlias({"TABLE_SCHEM"})

    private String schemaName;




    private List<TableColumn> columnList;




    private List<TableIndex> indexList;




    private List<ForeignKeyInfo> foreignKeyList;

    private List<CheckConstraintInfo> checkConstraintList;




    private String dbType;




    @JsonAlias("TABLE_CAT")
    private String databaseName;




    @JsonAlias("TABLE_TYPE")
    private String type;




    private boolean pinned;




    private String ddl;




    @JsonAlias("TYPE_NAME")
    private String engine;


    private String charset;


    private String collate;


    private Long incrementValue;


    private String partition;


    private String tablespace;

    private Long rows;

    private Long dataLength;

    private String createTime;

    private String updateTime;
}
