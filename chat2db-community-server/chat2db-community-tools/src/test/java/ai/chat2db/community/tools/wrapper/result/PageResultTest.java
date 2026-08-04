package ai.chat2db.community.tools.wrapper.result;

import java.util.Arrays;
import java.util.Collections;

import ai.chat2db.community.tools.wrapper.result.web.WebPageResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the pagination off-by-one: when the total row count
 * is an exact multiple of pageSize, the last page must report hasNextPage=false
 * (previously {@code pageSize * pageNo <= total} produced a phantom next page).
 */
class PageResultTest {

    @Test
    void exactMultipleOfPageSizeHasNoNextPage() {
        PageResult<Integer> result = PageResult.of(Arrays.asList(11, 12, 13, 14, 15, 16, 17, 18, 19, 20), 20L, 2L, 10L);
        assertFalse(result.getHasNextPage());
    }

    @Test
    void earlierPageStillHasNextPage() {
        PageResult<Integer> result = PageResult.of(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 20L, 1L, 10L);
        assertTrue(result.getHasNextPage());
    }

    @Test
    void partialLastPageHasNoNextPage() {
        PageResult<Integer> result = PageResult.of(Arrays.asList(11, 12, 13), 13L, 2L, 10L);
        assertFalse(result.getHasNextPage());
    }

    @Test
    void webPageResultExactMultipleOfPageSizeHasNoNextPage() {
        WebPageResult<Integer> result = WebPageResult.of(Arrays.asList(11, 12, 13, 14, 15, 16, 17, 18, 19, 20), 20L, 2, 10);
        assertFalse(result.getData().calculateHasNextPage());
        assertFalse(result.getHasNextPage());
    }

    @Test
    void webPageResultEarlierPageStillHasNextPage() {
        WebPageResult<Integer> result = WebPageResult.of(Collections.singletonList(1), 20L, 1, 10);
        assertTrue(result.getHasNextPage());
    }
}
