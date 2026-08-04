package ai.chat2db.plugin.generic;

import ai.chat2db.community.domain.api.model.metadata.ColumnType;
import ai.chat2db.community.domain.api.model.metadata.Type;
import java.sql.DatabaseMetaData;
import java.util.Arrays;
import java.util.List;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.factory.Mappers;

@Mapper
public interface IGenericMetaDataConverter {

    IGenericMetaDataConverter INSTANCE = Mappers.getMapper(IGenericMetaDataConverter.class);

    ColumnType type2columnType(Type type);

    List<ColumnType> type2columnType(List<Type> types);

    /**
     * Derive only capabilities that JDBC {@code getTypeInfo} reports directly. These flags control
     * which clauses the table editor may emit, so unknown capabilities must remain disabled.
     */
    @AfterMapping
    default void fillSupportFlags(Type type, @MappingTarget ColumnType columnType) {
        if (type == null || columnType == null) {
            return;
        }
        columnType.setSupportLength(hasCreateParameter(type, "length")
                || hasCreateParameter(type, "precision"));
        columnType.setSupportScale(hasCreateParameter(type, "scale"));
        columnType.setSupportNullable(type.getNullable() != null
                && type.getNullable() == DatabaseMetaData.typeNullable);
        // JDBC TypeInfo has no field that proves DEFAULT support.
        columnType.setSupportDefaultValue(false);
        columnType.setSupportAutoIncrement(Boolean.TRUE.equals(type.getAutoIncrement()));
    }

    private static boolean hasCreateParameter(Type type, String expected) {
        if (type.getCreateParams() == null) {
            return false;
        }
        return Arrays.stream(type.getCreateParams().split(","))
                .map(String::trim)
                .anyMatch(expected::equalsIgnoreCase);
    }
}
