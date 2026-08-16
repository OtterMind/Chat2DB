package ai.chat2db.plugin.clickhouse.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClickHouseColumnTypeEnumTest {

    @Test
    void buildsBareDecimalWhenPrecisionIsMissing() {
        assertEquals("`amount` Decimal", buildDecimal(null, null));
    }

    @Test
    void defaultsScaleToZeroWhenOnlyPrecisionIsAvailable() {
        assertEquals("`amount` Decimal(10,0)", buildDecimal(10, null));
    }

    @Test
    void rendersExplicitZeroScale() {
        assertEquals("`amount` Decimal(10,0)", buildDecimal(10, 0));
    }

    @Test
    void rendersExplicitScale() {
        assertEquals("`amount` Decimal(10,2)", buildDecimal(10, 2));
    }

    private String buildDecimal(Integer precision, Integer scale) {
        TableColumn column = new TableColumn();
        column.setName("amount");
        column.setColumnType("DECIMAL");
        column.setColumnSize(precision);
        column.setDecimalDigits(scale);
        return ClickHouseColumnTypeEnum.Decimal.buildCreateColumnSql(column).trim();
    }
}
