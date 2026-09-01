package ai.chat2db.plugin.mysql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MysqlMetaDataColumnTypeTest {

    @Test
    void extractsArgumentsThroughTheOuterClosingParenthesis() {
        assertEquals("'a(b)','c)'", MysqlMetaData.extractColumnTypeArguments("enum('a(b)','c)')"));
        assertEquals("10,2", MysqlMetaData.extractColumnTypeArguments("decimal(10,2) unsigned"));
        assertNull(MysqlMetaData.extractColumnTypeArguments("varchar"));
    }

    @Test
    void boundsUnsignedLongTextOctetLengthWithoutOverflowingMetadata() {
        assertEquals(Integer.MAX_VALUE, MysqlMetaData.boundedCharOctetLength(4_294_967_295L, false));
        assertEquals(1024, MysqlMetaData.boundedCharOctetLength(1024L, false));
        assertNull(MysqlMetaData.boundedCharOctetLength(0L, true));
    }
}
