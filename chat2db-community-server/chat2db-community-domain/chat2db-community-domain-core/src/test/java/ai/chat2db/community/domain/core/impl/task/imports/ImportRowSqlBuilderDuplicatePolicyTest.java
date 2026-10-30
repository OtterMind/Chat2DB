package ai.chat2db.community.domain.core.impl.task.imports;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.model.task.ImportDuplicatePolicy;
import ai.chat2db.community.domain.api.model.task.TaskErrorCode;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.tools.util.I18nUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parameterized INSERT shape per duplicate policy and dialect. An unsupported combination must
 * fail loudly: a silent fallback to a plain INSERT would turn every duplicate key into a task
 * failure the user did not ask for.
 */
class ImportRowSqlBuilderDuplicatePolicyTest {

    /** The dialect-quoted statement the SQL builder produces; the policy only reshapes it. */
    private static final String QUOTED_INSERT = "INSERT INTO shop.orders (ID,NAME) VALUES (?,?)";

    @Test
    void mysqlProtocolFamilyUsesInsertIgnore() {
        for (DatabaseTypeEnum dialect : List.of(DatabaseTypeEnum.MYSQL, DatabaseTypeEnum.MARIADB,
                DatabaseTypeEnum.TIDB, DatabaseTypeEnum.DORIS, DatabaseTypeEnum.STARROCKS,
                DatabaseTypeEnum.OCEANBASE)) {
            assertEquals("INSERT IGNORE INTO shop.orders (ID,NAME) VALUES (?,?)",
                    ImportRowSqlBuilder.ignoreVariant(QUOTED_INSERT, dialect),
                    dialect.name());
        }
    }

    @Test
    void postgresUsesOnConflictDoNothing() {
        assertEquals(QUOTED_INSERT + " ON CONFLICT DO NOTHING",
                ImportRowSqlBuilder.ignoreVariant(QUOTED_INSERT, DatabaseTypeEnum.POSTGRESQL));
    }

    @Test
    void unsupportedDialectIsRejectedWithTheDialectInTheMessage() throws Exception {
        Object previous = installMessages();
        try {
            for (DatabaseTypeEnum dialect : List.of(DatabaseTypeEnum.ORACLE, DatabaseTypeEnum.SQLSERVER,
                    DatabaseTypeEnum.CLICKHOUSE, DatabaseTypeEnum.DUCKDB)) {
                TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                        () -> ImportRowSqlBuilder.ignoreVariant(QUOTED_INSERT, dialect));
                assertEquals(TaskErrorCode.IMPORT_FAILED.name(), failure.getCode());
                assertTrue(failure.publicMessage().contains(dialect.name()), failure.publicMessage());
                assertTrue(failure.publicMessage().contains("IGNORE"), failure.publicMessage());
            }
            TaskExecutionException unknown = assertThrows(TaskExecutionException.class,
                    () -> ImportRowSqlBuilder.ignoreVariant(QUOTED_INSERT, null));
            assertEquals(TaskErrorCode.IMPORT_FAILED.name(), unknown.getCode());
        } finally {
            restoreMessages(previous);
        }
    }

    @Test
    void keepsTheQuotedStatementAndItsPlaceholdersUntouched() {
        // The policy must not re-render identifiers or placeholders: the dialect SQL builder did that.
        assertEquals("INSERT IGNORE INTO `order` (`select`,`NAME`) VALUES (?,?)",
                ImportRowSqlBuilder.ignoreVariant(
                        "INSERT INTO `order` (`select`,`NAME`) VALUES (?,?)", DatabaseTypeEnum.MYSQL));
        // A source without a mapped column keeps the shape the driver can reject clearly.
        assertEquals("INSERT IGNORE INTO t VALUES ()",
                ImportRowSqlBuilder.ignoreVariant("INSERT INTO t VALUES ()", DatabaseTypeEnum.MYSQL));
    }

    @Test
    void policyValuesDefaultToInsertAndUnknownValuesAreRejected() {
        assertEquals(ImportDuplicatePolicy.INSERT, ImportDuplicatePolicy.from(null));
        assertEquals(ImportDuplicatePolicy.INSERT, ImportDuplicatePolicy.from(" "));
        assertEquals(ImportDuplicatePolicy.INSERT, ImportDuplicatePolicy.from("insert"));
        assertEquals(ImportDuplicatePolicy.IGNORE, ImportDuplicatePolicy.from(" ignore "));
        assertThrows(IllegalArgumentException.class, () -> ImportDuplicatePolicy.from("UPSERT"));
        assertThrows(IllegalArgumentException.class, () -> ImportDuplicatePolicy.from("REPLACE"));
    }

    private static Object installMessages() throws Exception {
        Field field = I18nUtils.class.getDeclaredField("messageSourceStatic");
        field.setAccessible(true);
        Object previous = field.get(null);
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("import.duplicate.policyUnsupported", LocaleContextHolder.getLocale(),
                "Unsupported duplicate policy {0} for dialect {1}");
        field.set(null, messages);
        return previous;
    }

    private static void restoreMessages(Object previous) throws Exception {
        Field field = I18nUtils.class.getDeclaredField("messageSourceStatic");
        field.setAccessible(true);
        field.set(null, previous);
    }
}
