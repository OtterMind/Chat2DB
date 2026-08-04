package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.spi.DefaultSQLIdentifierProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenericSqlCompletionEngineTest {

    private final DefaultSQLIdentifierProcessor processor = new DefaultSQLIdentifierProcessor();

    @Test
    void normalizeIdentifierPartUnquotesOnlyOuterDelimiters() {
        assertEquals("A\"B", GenericSqlCompletionEngine.normalizeIdentifierPart(processor, "\"A\"\"B\""));
        assertEquals("A\"B", GenericSqlCompletionEngine.normalizeIdentifierPart(processor, "A\"B"));
        assertNull(GenericSqlCompletionEngine.normalizeIdentifierPart(processor, null));
    }
}
