package ai.chat2db.community.web.api.model.response.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnResponse {


    private String oldName;


    private String name;


    private String tableName;


    private String columnType;


    private Integer dataType;


    private String defaultValue;


    private Boolean autoIncrement;


    private String comment;


    private Boolean primaryKey;


    private String schemaName;


    private String databaseName;


    private String typeName;


    private Integer columnSize;


    private Integer bufferLength;


    private Integer decimalDigits;


    private Integer numPrecRadix;


    private Integer nullableInt;


    private Integer sqlDataType;


    private Integer sqlDatetimeSub;


    private Integer charOctetLength;


    private Integer ordinalPosition;


    private Integer nullable;


    private Boolean generatedColumn;

    private String generationExpression;

    private String generatedColumnType;


    private String extent;


    private String editStatus;

}
