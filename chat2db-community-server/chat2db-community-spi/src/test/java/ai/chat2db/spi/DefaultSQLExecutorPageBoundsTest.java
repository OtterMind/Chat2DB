package ai.chat2db.spi;

import ai.chat2db.community.tools.constant.IEasyToolsConstant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultSQLExecutorPageBoundsTest {

    @Test
    void defaultsAndInvalidValuesUseFirstPageAndMaximumPageSize() {
        assertBounds(null, null, 1, IEasyToolsConstant.MAX_PAGE_SIZE, 0);
        assertBounds(0, 0, 1, IEasyToolsConstant.MAX_PAGE_SIZE, 0);
        assertBounds(-1, IEasyToolsConstant.MAX_PAGE_SIZE + 1,
                1, IEasyToolsConstant.MAX_PAGE_SIZE, 0);
    }

    @Test
    void clampsPageNumberWithoutOverflowingOffset() {
        int pageSize = 1000;
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
