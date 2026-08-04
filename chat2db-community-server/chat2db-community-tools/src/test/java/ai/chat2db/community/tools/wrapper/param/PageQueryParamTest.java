package ai.chat2db.community.tools.wrapper.param;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for {@link PageQueryParam#andOrderBy(OrderBy)} NPEing
 * when invoked before any {@link PageQueryParam#orderBy(OrderBy)} call
 * (orderByList was null), and for orderBy() accumulating instead of replacing.
 */
class PageQueryParamTest {

    @Test
    void andOrderByBeforeOrderByDoesNotThrow() {
        PageQueryParam param = assertDoesNotThrow(() -> new PageQueryParam().andOrderBy(OrderBy.asc("x")));
        assertEquals(1, param.getOrderByList().size());
        assertEquals("x", param.getOrderByList().get(0).getOrderConditionName());
    }

    @Test
    void orderByThenAndOrderByAccumulates() {
        PageQueryParam param = new PageQueryParam()
            .orderBy(OrderBy.asc("a"))
            .andOrderBy(OrderBy.desc("b"));
        assertEquals(2, param.getOrderByList().size());
    }

    @Test
    void repeatedOrderByReplacesPreviousValue() {
        PageQueryParam param = new PageQueryParam()
            .orderBy(OrderBy.asc("a"))
            .orderBy(OrderBy.asc("b"));
        assertEquals(1, param.getOrderByList().size());
        assertEquals("b", param.getOrderByList().get(0).getOrderConditionName());
    }

    @Test
    void nullOrderByClearsPreviousValueWithoutAddingNull() {
        PageQueryParam param = new PageQueryParam().orderBy(OrderBy.asc("a"));
        assertDoesNotThrow(() -> param.orderBy((OrderBy) null));
        assertNotNull(param.getOrderByList());
        assertTrue(param.getOrderByList().isEmpty());
    }

    @Test
    void nullOrderConditionIsIgnoredSafely() {
        PageQueryParam param = new PageQueryParam().andOrderBy(OrderBy.asc("a"));
        assertDoesNotThrow(() -> param.andOrderBy((IOrderCondition) null));
        assertEquals(1, param.getOrderByList().size());

        assertDoesNotThrow(() -> param.orderBy((IOrderCondition) null));
        assertTrue(param.getOrderByList().isEmpty());
    }
}
