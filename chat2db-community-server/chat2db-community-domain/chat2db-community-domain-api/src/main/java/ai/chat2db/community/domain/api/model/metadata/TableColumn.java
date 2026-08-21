package ai.chat2db.community.domain.api.model.metadata;

import java.io.Serializable;
import java.util.Objects;

import ai.chat2db.community.domain.api.deserializer.YesNoBooleanDeserializer;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TableColumn implements Serializable {
    private static final long serialVersionUID = 1L;




    private TableColumn oldColumn;



    private String oldName;




    @JsonAlias({"COLUMN_NAME","column_name"})
    private String name;




    @JsonAlias({"TABLE_NAME","table_name"})
    private String tableName;




    @JsonAlias({"TYPE_NAME","type_name"})
    private String columnType;




    @JsonAlias({"DATA_TYPE","data_type"})
    private Integer dataType;





    @JsonAlias({"COLUMN_DEF","column_def"})
    private String defaultValue;





    @JsonAlias({"IS_AUTOINCREMENT","is_autoincrement"})
    @JsonDeserialize(using = YesNoBooleanDeserializer.class)
    private Boolean autoIncrement;




    @JsonAlias({"REMARKS","remarks"})
    private String comment;




    private Boolean primaryKey;





    private String primaryKeyName;





    private int primaryKeyOrder;




    @JsonAlias({"TABLE_SCHEM","table_schem"})
    private String schemaName;




    @JsonAlias({"TABLE_CAT","table_cat"})
    private String databaseName;




    @JsonAlias({"COLUMN_SIZE","column_size"})
    private Integer columnSize;




    private Integer bufferLength;




    @JsonAlias({"DECIMAL_DIGITS","decimal_digits"})
    private Integer decimalDigits;




    @JsonAlias({"NUM_PREC_RADIX","num_prec_radix"})
    private Integer numPrecRadix;





    private Integer sqlDataType;





    private Integer sqlDatetimeSub;




    private Integer charOctetLength;




    @JsonAlias({"ORDINAL_POSITION","ordinal_position"})
    private Integer ordinalPosition;




    @JsonAlias({"nullable","NULLABLE"})
    @JSONField(name = "nullable")
    private Integer nullable;




    private Boolean generatedColumn;

    private Boolean visible;


    private String extent;


    private String editStatus;

    private String charSetName;

    private String collationName;
    private String value;
    private String unit;
    private Boolean sparse;
    private String defaultConstraintName;
    private Integer seed;
    private Integer increment;


    private Boolean onUpdateCurrentTimestamp;

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true;}
        if (o == null || getClass() != o.getClass()) { return false;}
        TableColumn that = (TableColumn) o;
        return Objects.equals(name, that.name) && Objects.equals(tableName, that.tableName) && Objects.equals(columnType, that.columnType) && Objects.equals(defaultValue, that.defaultValue) && Objects.equals(autoIncrement, that.autoIncrement) && Objects.equals(comment, that.comment) && Objects.equals(columnSize, that.columnSize) && Objects.equals(decimalDigits, that.decimalDigits) && Objects.equals(numPrecRadix, that.numPrecRadix) && Objects.equals(sqlDataType, that.sqlDataType) && Objects.equals(ordinalPosition, that.ordinalPosition) && Objects.equals(nullable, that.nullable) && Objects.equals(extent, that.extent) && Objects.equals(charSetName, that.charSetName) && Objects.equals(collationName, that.collationName) && Objects.equals(value, that.value) && Objects.equals(unit, that.unit) && Objects.equals(sparse, that.sparse) && Objects.equals(defaultConstraintName, that.defaultConstraintName) && Objects.equals(seed, that.seed) && Objects.equals(increment, that.increment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, tableName, columnType, defaultValue, autoIncrement, comment, columnSize, decimalDigits, numPrecRadix, sqlDataType, ordinalPosition, nullable, extent, charSetName, collationName, value, unit, sparse, defaultConstraintName, seed, increment);
    }
}
