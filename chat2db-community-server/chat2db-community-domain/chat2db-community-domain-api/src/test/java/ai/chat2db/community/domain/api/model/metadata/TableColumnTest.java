package ai.chat2db.community.domain.api.model.metadata;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class TableColumnTest {

    @Test
    void generatedColumnDefinitionParticipatesInEquality() {
        TableColumn original = generatedColumn("price * 0.9", "VIRTUAL", true);

        assertNotEquals(original, generatedColumn("price * 0.8", "VIRTUAL", true));
        assertNotEquals(original, generatedColumn("price * 0.9", "STORED", true));
        assertNotEquals(original, generatedColumn("price * 0.9", "VIRTUAL", false));
    }

    private static TableColumn generatedColumn(String expression, String storageType, boolean generated) {
        return TableColumn.builder()
                .name("discounted")
                .tableName("products")
                .columnType("DECIMAL")
                .columnSize(10)
                .decimalDigits(2)
                .generatedColumn(generated)
                .generationExpression(expression)
                .generatedColumnType(storageType)
                .build();
    }
}
