package ai.chat2db.spi.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcUrlRewriterTest {

    @Test
    void appendsBatchRewriteWhenMissing() {
        assertEquals("jdbc:mysql://localhost:3306/app?rewriteBatchedStatements=true",
                JdbcUrlRewriter.withBatchRewrite("jdbc:mysql://localhost:3306/app"));
    }

    @Test
    void keepsExistingParameters() {
        assertEquals("jdbc:mysql://localhost:3306/app?useSSL=false&rewriteBatchedStatements=true",
                JdbcUrlRewriter.withBatchRewrite("jdbc:mysql://localhost:3306/app?useSSL=false"));
    }

    @Test
    void respectsExplicitUserValue() {
        String disabled = "jdbc:mysql://localhost:3306/app?rewriteBatchedStatements=false";
        assertEquals(disabled, JdbcUrlRewriter.withBatchRewrite(disabled));
    }

    @Test
    void appendsWhenTheNameOnlyAppearsInsideAnotherParameterValue() {
        assertEquals(
                "jdbc:mysql://localhost:3306/app?sessionVariables=rewriteBatchedStatements&rewriteBatchedStatements=true",
                JdbcUrlRewriter.withBatchRewrite(
                        "jdbc:mysql://localhost:3306/app?sessionVariables=rewriteBatchedStatements"));
    }

    @Test
    void respectsParameterWithoutValue() {
        String url = "jdbc:mysql://localhost:3306/app?rewriteBatchedStatements";
        assertEquals(url, JdbcUrlRewriter.withBatchRewrite(url));
    }

    @Test
    void keepsFragmentAtTheEnd() {
        assertEquals("jdbc:mysql://localhost:3306/app?rewriteBatchedStatements=true#frag",
                JdbcUrlRewriter.withBatchRewrite("jdbc:mysql://localhost:3306/app#frag"));
    }

    @Test
    void leavesOtherDriversUntouched() {
        assertEquals("jdbc:postgresql://localhost:5432/app",
                JdbcUrlRewriter.withBatchRewrite("jdbc:postgresql://localhost:5432/app"));
        assertEquals("jdbc:mariadb://localhost:3306/app",
                JdbcUrlRewriter.withBatchRewrite("jdbc:mariadb://localhost:3306/app"));
    }

    @Test
    void handlesBlankInput() {
        assertNull(JdbcUrlRewriter.withBatchRewrite(null));
        assertEquals("  ", JdbcUrlRewriter.withBatchRewrite("  "));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(JdbcUrlRewriter.withBatchRewrite("JDBC:MYSQL://localhost/app")
                .toLowerCase().endsWith("rewritebatchedstatements=true"));
        assertEquals("jdbc:mysql://localhost/app?REWRITEBATCHEDSTATEMENTS=TRUE",
                JdbcUrlRewriter.withBatchRewrite("jdbc:mysql://localhost/app?REWRITEBATCHEDSTATEMENTS=TRUE"));
        assertFalse(JdbcUrlRewriter.withBatchRewrite("jdbc:mysql://localhost/app")
                .contains("REWRITEBATCHEDSTATEMENTS"));
    }
}