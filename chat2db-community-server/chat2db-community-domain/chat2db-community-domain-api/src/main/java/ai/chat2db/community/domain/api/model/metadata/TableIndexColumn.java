package ai.chat2db.community.domain.api.model.metadata;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TableIndexColumn implements Serializable {
    private static final long serialVersionUID = 1L;




    @JsonAlias({"INDEX_NAME"})
    private String indexName;




    @JsonAlias ({"TABLE_NAME"})
    private String tableName;




    private String type;




    private String comment;




    @JsonAlias({"COLUMN_NAME"})
    private String columnName;




    @JsonAlias({"ORDINAL_POSITION"})
    private Short ordinalPosition;




    private String collation;





    @JsonAlias({"TABLE_SCHEM"})
    private String schemaName;




    @JsonAlias({"TABLE_CAT"})
    private String databaseName;




    @JsonAlias({"NON_UNIQUE"})
    private Boolean nonUnique;




    @JsonAlias({"INDEX_QUALIFIER"})
    private String indexQualifier;




    @JsonAlias({"ASC_OR_DESC"})
    private String ascOrDesc;




    @JsonAlias({"CARDINALITY"})
    private Long cardinality;




    @JsonAlias({"PAGES"})
    private Long pages;




    @JsonAlias({"FILTER_CONDITION"})
    private String filterCondition;


    private Long subPart;


    private String editStatus;

    private String expression;
}
