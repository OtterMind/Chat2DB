package ai.chat2db.community.domain.api.model.metadata;

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class ForeignKeyInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonAlias("PKTABLE_CAT")
    private String pkTableCat;

    @JsonAlias("PKTABLE_SCHEM")
    private String pkTableSchem;

    @JsonAlias("PKTABLE_NAME")
    private String pkTableName;

    @JsonAlias("PKCOLUMN_NAME")
    private String pkColumnName;

    @JsonAlias("FKTABLE_CAT")
    private String fkTableCat;

    @JsonAlias("FKTABLE_SCHEM")
    private String fkTableSchem;

    @JsonAlias("FKTABLE_NAME")
    private String fkTableName;

    @JsonAlias("FKCOLUMN_NAME")
    private String fkColumnName;

    @JsonAlias("KEY_SEQ")
    private short keySeq;

    @JsonAlias("UPDATE_RULE")
    private Short updateRule;

    @JsonAlias("DELETE_RULE")
    private Short deleteRule;

    @JsonAlias("FK_NAME")
    private String fkName;

    @JsonAlias("PK_NAME")
    private String pkName;

    @JsonAlias("DEFERRABILITY")
    private short deferrability;

    private String editStatus;

}
