package ai.chat2db.community.test.plugin.mysql;

import ai.chat2db.plugin.mysql.MysqlMetaData;
import ai.chat2db.plugin.mysql.MysqlPlugin;
import ai.chat2db.community.domain.api.enums.plugin.ResultSetEditorTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.result.ResultSetEditorMetadata;
import ai.chat2db.spi.IDbMetaData;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlResultSetEditorTypeTest {

    private final MysqlMetaData mysqlMetaData = new MysqlMetaData();
    private final IDbMetaData mysqlPluginMetaData = new MysqlPlugin().getDbMetaData();

    @Test
    void resolvesMysqlTemporalTypesFromTypeName() {
        assertEquals(ResultSetEditorTypeEnum.DATE,
                ResultSetEditorTypeEnum.from(mysqlMetaData.resolveResultSetEditorType("DATE", Types.DATE)));
        assertEquals(ResultSetEditorTypeEnum.TIME,
                ResultSetEditorTypeEnum.from(mysqlMetaData.resolveResultSetEditorType("TIME", Types.TIME)));
        assertEquals(ResultSetEditorTypeEnum.DATETIME,
                ResultSetEditorTypeEnum.from(mysqlMetaData.resolveResultSetEditorType("DATETIME", Types.TIMESTAMP)));
        assertEquals(ResultSetEditorTypeEnum.TIMESTAMP,
                ResultSetEditorTypeEnum.from(mysqlMetaData.resolveResultSetEditorType("TIMESTAMP", Types.TIMESTAMP)));
        assertEquals(ResultSetEditorTypeEnum.DATETIME,
                ResultSetEditorTypeEnum.from(mysqlMetaData.resolveResultSetEditorType("datetime(6)", Types.TIMESTAMP)));
        assertEquals(ResultSetEditorTypeEnum.TIMESTAMP,
                ResultSetEditorTypeEnum.from(mysqlMetaData.resolveResultSetEditorType("timestamp(6)", Types.TIMESTAMP)));
    }

    @Test
    void fallsBackToTextForOtherMysqlTypes() {
        assertEquals(ResultSetEditorTypeEnum.TEXT,
                ResultSetEditorTypeEnum.from(mysqlMetaData.resolveResultSetEditorType("VARCHAR", Types.VARCHAR)));
        assertEquals(ResultSetEditorTypeEnum.TEXT,
                ResultSetEditorTypeEnum.from(mysqlMetaData.resolveResultSetEditorType("DATETIMEOFFSET", null)));
    }

    @Test
    void resolvesMysqlEnumOptionsFromColumnMetadata() {
        TableColumn column = TableColumn.builder()
                .name("status")
                .columnType("ENUM")
                .dataType(Types.VARCHAR)
                .value("'draft','needs,review','can\\'t','a\\\\b','(nested)',''")
                .build();

        ResultSetEditorMetadata metadata = mysqlPluginMetaData.resolveResultSetEditorMetadata(column);

        assertEquals(ResultSetEditorTypeEnum.SELECT.getCode(), metadata.getEditorType());
        assertEquals(List.of("draft", "needs,review", "can't", "a\\b", "(nested)", ""),
                metadata.getEditorOptions().stream().map(option -> option.getValue()).toList());
        assertEquals(metadata.getEditorOptions().stream().map(option -> option.getValue()).toList(),
                metadata.getEditorOptions().stream().map(option -> option.getLabel()).toList());
    }

    @Test
    void resolvesMysqlSetOptionsAsMultiSelect() {
        TableColumn setColumn = TableColumn.builder()
                .columnType("SET")
                .dataType(Types.VARCHAR)
                .value("'one','two','can\\'t','a\\\\b'")
                .build();

        ResultSetEditorMetadata metadata = mysqlPluginMetaData.resolveResultSetEditorMetadata(setColumn);

        assertEquals(ResultSetEditorTypeEnum.MULTI_SELECT.getCode(), metadata.getEditorType());
        assertEquals(List.of("one", "two", "can't", "a\\b"),
                metadata.getEditorOptions().stream().map(option -> option.getValue()).toList());
    }

    @Test
    void leavesNonEnumSetAndMalformedOptionMetadataOnTheirExistingEditorType() {
        TableColumn varcharColumn = TableColumn.builder()
                .columnType("VARCHAR")
                .dataType(Types.VARCHAR)
                .build();
        TableColumn malformedEnum = TableColumn.builder()
                .name("broken_status")
                .columnType("ENUM")
                .dataType(Types.VARCHAR)
                .value("'one','two")
                .build();
        TableColumn malformedSet = TableColumn.builder()
                .name("broken_tags")
                .columnType("SET")
                .dataType(Types.VARCHAR)
                .value("'one','two")
                .build();
        TableColumn commaSet = TableColumn.builder()
                .name("ambiguous_comma_tags")
                .columnType("SET")
                .dataType(Types.VARCHAR)
                .value("'one','two,three'")
                .build();
        TableColumn emptyMemberSet = TableColumn.builder()
                .name("ambiguous_empty_tags")
                .columnType("SET")
                .dataType(Types.VARCHAR)
                .value("'one',''")
                .build();

        assertEquals(ResultSetEditorTypeEnum.TEXT.getCode(),
                mysqlPluginMetaData.resolveResultSetEditorMetadata(varcharColumn).getEditorType());
        ResultSetEditorMetadata malformedMetadata = mysqlPluginMetaData.resolveResultSetEditorMetadata(malformedEnum);
        assertEquals(ResultSetEditorTypeEnum.TEXT.getCode(), malformedMetadata.getEditorType());
        assertEquals(List.of(), malformedMetadata.getEditorOptions());
        ResultSetEditorMetadata malformedSetMetadata = mysqlPluginMetaData.resolveResultSetEditorMetadata(malformedSet);
        assertEquals(ResultSetEditorTypeEnum.TEXT.getCode(), malformedSetMetadata.getEditorType());
        assertEquals(List.of(), malformedSetMetadata.getEditorOptions());
        assertEquals(ResultSetEditorTypeEnum.TEXT.getCode(),
                mysqlPluginMetaData.resolveResultSetEditorMetadata(commaSet).getEditorType());
        assertEquals(ResultSetEditorTypeEnum.TEXT.getCode(),
                mysqlPluginMetaData.resolveResultSetEditorMetadata(emptyMemberSet).getEditorType());
    }

    @Test
    void nativeMysqlProvidesOptionsButCompatibilitySubclassesUseDefaultMetadata() {
        MysqlMetaData compatibilityDialect = new MysqlMetaData() {
        };
        TableColumn enumColumn = TableColumn.builder()
                .columnType("ENUM")
                .dataType(Types.VARCHAR)
                .value("'one','two'")
                .build();

        ResultSetEditorMetadata nativeEnumMetadata =
                mysqlPluginMetaData.resolveResultSetEditorMetadata(enumColumn);
        assertEquals(ResultSetEditorTypeEnum.SELECT.getCode(), nativeEnumMetadata.getEditorType());
        assertEquals(List.of("one", "two"), nativeEnumMetadata.getEditorOptions().stream()
                .map(option -> option.getValue()).toList());
        assertEquals(ResultSetEditorTypeEnum.TEXT.getCode(),
                compatibilityDialect.resolveResultSetEditorMetadata(enumColumn).getEditorType());
        assertEquals(List.of(), compatibilityDialect.resolveResultSetEditorMetadata(enumColumn).getEditorOptions());

        TableColumn setColumn = TableColumn.builder()
                .columnType("SET")
                .dataType(Types.VARCHAR)
                .value("'one','two'")
                .build();
        ResultSetEditorMetadata nativeSetMetadata =
                mysqlPluginMetaData.resolveResultSetEditorMetadata(setColumn);
        assertEquals(ResultSetEditorTypeEnum.MULTI_SELECT.getCode(), nativeSetMetadata.getEditorType());
        assertEquals(List.of("one", "two"), nativeSetMetadata.getEditorOptions().stream()
                .map(option -> option.getValue()).toList());
        assertEquals(ResultSetEditorTypeEnum.TEXT.getCode(),
                compatibilityDialect.resolveResultSetEditorMetadata(setColumn).getEditorType());
        assertEquals(List.of(), compatibilityDialect.resolveResultSetEditorMetadata(setColumn).getEditorOptions());
    }
}
