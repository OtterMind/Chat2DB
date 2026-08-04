package ai.chat2db.community.start.ai.subscription.appserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Chat2dbMcpToolPolicyTest {

    @Test
    void formatUiSelectionContextIncludesPriorityIds() {
        String block = Chat2dbMcpToolPolicy.formatUiSelectionContext(42L, "3dm_international", null);
        assertTrue(block.contains("dataSourceId=42"));
        assertTrue(block.contains("databaseName=3dm_international"));
        assertTrue(block.contains("priority target"));
        assertFalse(block.toLowerCase().contains("password"));
    }

    @Test
    void formatUiSelectionContextEmptyWithoutSelection() {
        assertTrue(Chat2dbMcpToolPolicy.formatUiSelectionContext(null, null, null).isEmpty());
        assertTrue(Chat2dbMcpToolPolicy.formatUiSelectionContext(null, "  ", " ").isEmpty());
    }
}
