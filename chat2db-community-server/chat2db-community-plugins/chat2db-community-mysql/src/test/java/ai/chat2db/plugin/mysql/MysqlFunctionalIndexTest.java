package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.plugin.mysql.enums.type.MysqlIndexTypeEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MysqlFunctionalIndexTest {

    @Test
    void buildsFunctionalIndexWithExactlyOneExpressionWrapper() {
        TableIndex index = functionalIndex(null);

        assertEquals("INDEX `idx_lower_email` ((lower(`email`)))",
                MysqlIndexTypeEnum.NORMAL.buildIndexScript(index));
    }

    @Test
    void buildsFunctionalIndexModificationAndDrop() {
        TableIndex modified = functionalIndex(EditStatusEnum.MODIFY.name());
        modified.setOldName("idx_old_email");
        TableIndex deleted = functionalIndex(EditStatusEnum.DELETE.name());
        deleted.setOldName("idx_old_email");

        assertEquals("DROP INDEX `idx_old_email`,\nADD INDEX `idx_lower_email` ((lower(`email`)))",
                MysqlIndexTypeEnum.NORMAL.buildModifyIndex(modified));
        assertEquals("DROP INDEX `idx_old_email`", MysqlIndexTypeEnum.NORMAL.buildModifyIndex(deleted));
    }

    @Test
    void readsBackFunctionalIndexExpressionFromShowIndex() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("COLUMN_NAME", null);
        values.put("SEQ_IN_INDEX", (short) 1);
        values.put("COLLATION", "A");
        values.put("CARDINALITY", 5L);
        values.put("SUB_PART", 0L);
        values.put("Expression", "lower(`email`)");
        ResultSet resultSet = resultSet(values);

        TableIndexColumn column = MysqlMetaData.toTableIndexColumn(resultSet);

        assertNull(column.getColumnName());
        assertEquals("lower(`email`)", column.getExpression());
    }

    private static TableIndex functionalIndex(String editStatus) {
        return TableIndex.builder()
                .name("idx_lower_email")
                .type(MysqlIndexTypeEnum.NORMAL.getName())
                .editStatus(editStatus)
                .columnList(List.of(TableIndexColumn.builder().expression("((lower(`email`)))").build()))
                .build();
    }

    private static ResultSet resultSet(Map<String, Object> values) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    if ("getString".equals(method.getName())) {
                        return values.get(arguments[0]);
                    }
                    if ("getShort".equals(method.getName())) {
                        return (short) 1;
                    }
                    if ("getLong".equals(method.getName())) {
                        return 5L;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
