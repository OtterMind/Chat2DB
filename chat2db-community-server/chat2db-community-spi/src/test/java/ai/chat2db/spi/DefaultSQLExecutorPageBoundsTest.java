package ai.chat2db.spi;

import ai.chat2db.community.tools.constant.IEasyToolsConstant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultSQLExecutorPageBoundsTest {

    @Test
    void defaultsAndInvalidValuesUseFirstPageAndDefaultPageSize() {
        assertBounds(null, null, 1, IEasyToolsConstant.DEFAULT_PAGE_SIZE, 0);
        assertBounds(0, 0, 1, IEasyToolsConstant.DEFAULT_PAGE_SIZE, 0);
        assertBounds(-1, -1, 1, IEasyToolsConstant.DEFAULT_PAGE_SIZE, 0);
    }

    @Test
    void preservesPageSizesAboveTheDefault() {
        assertBounds(1, 5000, 1, 5000, 0);
        assertBounds(2, 5000, 2, 5000, 5000);
        assertBounds(1, Integer.MAX_VALUE, 1, Integer.MAX_VALUE, 0);
    }

    @Test
    void clampsPageNumberWithoutOverflowingOffset() {
        int pageSize = IEasyToolsConstant.DEFAULT_PAGE_SIZE;
        int maxPageNo = Integer.MAX_VALUE / pageSize + 1;
        int maxOffset = (maxPageNo - 1) * pageSize;

        assertBounds(Integer.MAX_VALUE, pageSize, maxPageNo, pageSize, maxOffset);
        assertBounds(Integer.MAX_VALUE, 1, Integer.MAX_VALUE, 1, Integer.MAX_VALUE - 1);
    }

    private static void assertBounds(Integer requestedPageNo, Integer requestedPageSize,
                                     int pageNo, int pageSize, int offset) {
        DefaultSQLExecutor.PageBounds bounds =
                DefaultSQLExecutor.normalizePageBounds(requestedPageNo, requestedPageSize);
        assertEquals(pageNo, bounds.pageNo());
        assertEquals(pageSize, bounds.pageSize());
        assertEquals(offset, bounds.offset());
    }
}
