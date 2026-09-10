package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.task.ArtifactDraft;
import ai.chat2db.community.domain.api.model.task.ImportOptions;
import ai.chat2db.community.domain.api.model.task.ImportScope;
import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import ai.chat2db.community.domain.api.model.task.TaskCancelledException;
import ai.chat2db.community.domain.api.model.task.TaskExecutionException;
import ai.chat2db.community.domain.api.model.task.TaskTargetSnapshot;
import ai.chat2db.community.domain.api.service.task.TaskCancelable;
import ai.chat2db.community.domain.api.service.task.TaskExecutionContext;
import ai.chat2db.plugin.h2.H2Plugin;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLImporterConnectionSafetyTest {

    private static final String H2 = "H2";
    private static final String MYSQL = "MYSQL";
    private static final String MARIADB = "MARIADB";
    private static final String POSTGRESQL = "POSTGRESQL";
    private static final String SQLSERVER = "SQLSERVER";
    private static final String ORACLE = "ORACLE";

    @TempDir
    Path tempDirectory;

    private final Map<String, IPlugin> previousPlugins = new HashMap<>();
    private String databaseUrl;
    private Connection physical;
    private ConnectInfo connectInfo;

    @BeforeEach
    void setUp() throws Exception {
        databaseUrl = "jdbc:h2:mem:sql_import_safety_"
                + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        physical = DriverManager.getConnection(databaseUrl, "sa", "");
        try (Statement statement = physical.createStatement()) {
            statement.execute("CREATE TABLE TARGET_ROWS (ID INT PRIMARY KEY, NAME VARCHAR(32))");
        }
        installPlugin(H2);
        useConnection(H2, physical, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        Chat2DBContext.removeContext();
        for (Map.Entry<String, IPlugin> entry : previousPlugins.entrySet()) {
            if (entry.getValue() == null) {
                Chat2DBContext.PLUGIN_MAP.remove(entry.getKey());
            } else {
                Chat2DBContext.PLUGIN_MAP.put(entry.getKey(), entry.getValue());
            }
        }
        if (physical != null && !physical.isClosed()) {
            physical.close();
        }
    }

    @Test
    void cancellationImmediatelyBeforeCommitRollsBackWithoutCallingCommit() throws Exception {
        ConnectionFaults faults = new ConnectionFaults(physical);
        Connection guarded = faults.connection();
        useConnection(H2, guarded, null);
        RecordingContext context = new RecordingContext(physical, true);

        assertThrows(TaskCancelledException.class,
                () -> new SQLImporter().run(spec(sql("cancel.sql",
                        "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), context));

        assertEquals(0, faults.commitCalls);
        assertEquals(1, faults.rollbackCalls);
        assertTrue(guarded.getAutoCommit());
        assertEquals(0L, committedRowCount());
    }

    @Test
    void entersTaskCommitPhaseBeforeCallingJdbcCommit() throws Exception {
        List<String> events = new ArrayList<>();
        ConnectionFaults faults = new ConnectionFaults(physical);
        faults.beforeCommit = () -> events.add("jdbcCommit");
        useConnection(H2, faults.connection(), null);
        RecordingContext context = new RecordingContext() {
            @Override
            public void enterCommitPhase() {
                events.add("taskCommitPhase");
                super.enterCommitPhase();
            }
        };

        new SQLImporter().run(spec(sql("commit-phase.sql",
                "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), context);

        assertEquals(List.of("taskCommitPhase", "jdbcCommit"), events);
        assertEquals(1L, committedRowCount());
    }

    @Test
    void dirtyOrTransactionStartFailureDetachesAndClosesTheConnection() throws Exception {
        physical.setAutoCommit(false);
        TaskExecutionException dirty = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("dirty.sql",
                        "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext()));
        assertTrue(dirty.getMessage().contains("clean auto-commit"));
        assertNull(connectInfo.getConnection());
        assertTrue(physical.isClosed());

        physical = DriverManager.getConnection(databaseUrl, "sa", "");
        ConnectionFaults faults = new ConnectionFaults(physical);
        faults.failTransactionStart = true;
        Connection guarded = faults.connection();
        useConnection(H2, guarded, null);
        assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("start-failure.sql",
                        "INSERT INTO TARGET_ROWS VALUES (2, 'two');\n")), new RecordingContext()));
        assertNull(connectInfo.getConnection());
        assertTrue(physical.isClosed());
    }

    @Test
    void commitFailureIsOutcomeUnknownAndConnectionIsDiscardedWithoutRollback() throws Exception {
        ConnectionFaults faults = new ConnectionFaults(physical);
        faults.failCommit = true;
        Connection guarded = faults.connection();
        useConnection(H2, guarded, null);

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("commit-failure.sql",
                        "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext()));

        assertEquals("SQL_IMPORT_COMMIT_OUTCOME_UNKNOWN", failure.getCode());
        assertTrue(failure.getSafeReason().contains("before retrying"));
        assertEquals(1, faults.commitCalls);
        assertEquals(0, faults.rollbackCalls);
        assertNull(connectInfo.getConnection());
        assertTrue(physical.isClosed());
        assertEquals(0L, committedRowCount());
    }

    @Test
    void restoreFailureAfterSuccessfulCommitWarnsAndStillCompletes() throws Exception {
        ConnectionFaults faults = new ConnectionFaults(physical);
        faults.failRestoreAfterCommit = true;
        useConnection(H2, faults.connection(), null);
        RecordingContext context = new RecordingContext();

        new SQLImporter().run(spec(sql("restore-failure.sql",
                "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), context);

        assertEquals(1, faults.commitCalls);
        assertNull(connectInfo.getConnection());
        assertTrue(physical.isClosed());
        assertEquals(List.of("SQL_IMPORT_CONNECTION_DISCARDED"), context.warningCodes);
        assertEquals(1L, committedRowCount());
    }

    @Test
    void scopeViolationFailsBeforeStartingTheTransaction() throws Exception {
        ConnectionFaults faults = new ConnectionFaults(physical);
        useConnection(H2, faults.connection(), null);
        ImportTaskSpec mismatched = spec(sql("scope-violation.sql",
                "INSERT INTO OTHER_ROWS VALUES (1, 'one');\n"));

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(mismatched, new RecordingContext()));

        assertTrue(failure.getMessage().contains("outside the selected TABLE"));
        assertEquals(0, faults.transactionStartCalls);
        assertEquals(0, faults.commitCalls);
        assertEquals(0, faults.rollbackCalls);
    }

    @Test
    void mysqlPreflightLocksAndVerifiesInnoDbBeforeExecutingDml() throws Exception {
        installPlugin(MYSQL);
        MysqlPreflightConnection mysql = new MysqlPreflightConnection(physical, "InnoDB");
        useConnection(MYSQL, mysql.connection(), "APP");

        new SQLImporter().run(spec(sql("mysql-innodb.sql",
                "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext());

        int lock = mysql.events.indexOf("lock");
        int engine = mysql.events.indexOf("engine");
        int dml = mysql.events.indexOf("dml");
        assertTrue(lock >= 0 && engine > lock && dml > engine, () -> mysql.events.toString());
        assertEquals(1L, committedRowCount());
    }

    @Test
    void mysqlPreflightRejectsNonTransactionalAndCrossDatabaseTargetsBeforeDml() throws Exception {
        installPlugin(MYSQL);
        MysqlPreflightConnection myisam = new MysqlPreflightConnection(physical, "MyISAM");
        useConnection(MYSQL, myisam.connection(), "APP");
        TaskExecutionException engineFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-myisam.sql",
                        "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext()));
        assertTrue(engineFailure.getMessage().contains("MyISAM"));
        assertFalse(myisam.events.contains("dml"));
        assertEquals(0L, committedRowCount());

        MysqlPreflightConnection crossDatabase = new MysqlPreflightConnection(physical, "InnoDB");
        useConnection(MYSQL, crossDatabase.connection(), "APP");
        ImportTaskSpec crossDatabaseSpec = spec(sql("mysql-cross-database.sql",
                "INSERT INTO other.TARGET_ROWS VALUES (2, 'two');\n"));
        crossDatabaseSpec.getTarget().setDatabaseName("other");
        TaskExecutionException targetFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(crossDatabaseSpec, new RecordingContext()));
        assertTrue(targetFailure.getMessage().contains("do not match"));
        assertFalse(crossDatabase.events.contains("lock"));
        assertFalse(crossDatabase.events.contains("dml"));
        assertEquals(0L, committedRowCount());
    }

    @Test
    void mysqlPreflightRejectsTriggersAndUnprovenTriggerVisibilityBeforeDml() throws Exception {
        installPlugin(MYSQL);
        MysqlPreflightConnection triggered = new MysqlPreflightConnection(physical, "InnoDB", 1,
                "GRANT TRIGGER ON `APP`.`TARGET_ROWS` TO 'importer'@'%'");
        useConnection(MYSQL, triggered.connection(), "APP");

        TaskExecutionException triggerFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-trigger.sql",
                        "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext()));
        assertTrue(triggerFailure.getMessage().contains("trigger(s)"));
        assertFalse(triggered.events.contains("dml"));

        MysqlPreflightConnection invisible = new MysqlPreflightConnection(physical, "InnoDB", 0,
                "GRANT INSERT ON `APP`.`TARGET_ROWS` TO 'importer'@'%'");
        useConnection(MYSQL, invisible.connection(), "APP");
        TaskExecutionException visibilityFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-trigger-invisible.sql",
                        "INSERT INTO TARGET_ROWS VALUES (2, 'two');\n")), new RecordingContext()));
        assertTrue(visibilityFailure.getMessage().contains("trigger metadata visibility"));
        assertFalse(invisible.events.contains("triggers"));
        assertFalse(invisible.events.contains("dml"));
    }

    @Test
    void mysqlPreflightHonorsPartialTriggerRevokesBeforeDml() throws Exception {
        installPlugin(MYSQL);
        String globalTriggerGrant = "GRANT INSERT, TRIGGER ON *.* TO 'importer'@'%'";
        MysqlPreflightConnection targetRevoked = new MysqlPreflightConnection(physical, "InnoDB", 0,
                List.of(globalTriggerGrant, "REVOKE TRIGGER ON `APP`.* FROM 'importer'@'%'"));
        useConnection(MYSQL, targetRevoked.connection(), "APP");

        TaskExecutionException targetFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-target-revoke.sql",
                        "INSERT INTO TARGET_ROWS (ID, NAME) VALUES (1, 'one');\n")), new RecordingContext()));
        assertTrue(targetFailure.getMessage().contains("trigger metadata visibility"));
        assertFalse(targetRevoked.events.contains("triggers"));
        assertFalse(targetRevoked.events.contains("dml"));

        MysqlPreflightConnection unrelatedRevoked = new MysqlPreflightConnection(physical, "InnoDB", 0,
                List.of(globalTriggerGrant, "REVOKE TRIGGER ON `OTHER`.* FROM 'importer'@'%'"));
        useConnection(MYSQL, unrelatedRevoked.connection(), "APP");
        new SQLImporter().run(spec(sql("mysql-unrelated-revoke.sql",
                "INSERT INTO TARGET_ROWS (ID, NAME) VALUES (2, 'two');\n")), new RecordingContext());
        assertTrue(unrelatedRevoked.events.contains("triggers"));
        assertTrue(unrelatedRevoked.events.contains("dml"));

        MysqlPreflightConnection malformedRevoke = new MysqlPreflightConnection(physical, "InnoDB", 0,
                List.of(globalTriggerGrant, "REVOKE unrecognized partial privilege"));
        useConnection(MYSQL, malformedRevoke.connection(), "APP");
        TaskExecutionException malformedFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-malformed-revoke.sql",
                        "INSERT INTO TARGET_ROWS (ID, NAME) VALUES (3, 'three');\n")), new RecordingContext()));
        assertTrue(malformedFailure.getMessage().contains("trigger metadata visibility"));
        assertFalse(malformedRevoke.events.contains("triggers"));
        assertFalse(malformedRevoke.events.contains("dml"));
    }

    @Test
    void mysqlPreflightRejectsViewsAndGeneratedColumnsBeforeDml() throws Exception {
        installPlugin(MYSQL);
        MysqlPreflightConnection view = new MysqlPreflightConnection(physical, "InnoDB");
        view.tableType = "VIEW";
        useConnection(MYSQL, view.connection(), "APP");
        TaskExecutionException viewFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-view.sql",
                        "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext()));
        assertTrue(viewFailure.getMessage().contains("not a base table"));
        assertFalse(view.events.contains("dml"));

        MysqlPreflightConnection generated = new MysqlPreflightConnection(physical, "InnoDB");
        generated.generatedColumnCount = 1;
        useConnection(MYSQL, generated.connection(), "APP");
        TaskExecutionException generatedFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-generated.sql",
                        "INSERT INTO TARGET_ROWS VALUES (2, 'two');\n")), new RecordingContext()));
        assertTrue(generatedFailure.getMessage().contains("generated"));
        assertFalse(generated.events.contains("dml"));
    }

    @Test
    void mysqlPreflightRejectsSessionTemporaryTableShadowingBeforeDml() throws Exception {
        installPlugin(MYSQL);
        MysqlPreflightConnection shadowed = new MysqlPreflightConnection(physical, "InnoDB");
        shadowed.temporaryTableShadowsTarget = true;
        useConnection(MYSQL, shadowed.connection(), "APP");

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-temporary-shadow.sql",
                        "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext()));

        assertTrue(failure.getMessage().contains("session temporary table shadows"));
        assertTrue(shadowed.events.contains("show-create"));
        assertFalse(shadowed.events.contains("engine"));
        assertFalse(shadowed.events.contains("dml"));
    }

    @Test
    void mysql8030RequiresVisibleGipkMetadataBeforeDml() throws Exception {
        installPlugin(MYSQL);
        MysqlPreflightConnection mysql = new MysqlPreflightConnection(physical, "InnoDB");
        mysql.productVersion = "8.0.30";
        mysql.gipkVisibility = "ON";
        useConnection(MYSQL, mysql.connection(), "APP");

        new SQLImporter().run(spec(sql("mysql-gipk-visible.sql",
                "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext());

        int gipk = mysql.events.indexOf("gipk-visibility");
        int lock = mysql.events.indexOf("lock");
        int dml = mysql.events.indexOf("dml");
        assertTrue(gipk >= 0 && lock > gipk && dml > lock, () -> mysql.events.toString());
        assertEquals(1L, committedRowCount());
    }

    @Test
    void mysql8030RejectsHiddenOrUnreadableGipkMetadataBeforeDml() throws Exception {
        installPlugin(MYSQL);
        MysqlPreflightConnection hidden = new MysqlPreflightConnection(physical, "InnoDB");
        hidden.productVersion = "8.0.30";
        hidden.gipkVisibility = "OFF";
        useConnection(MYSQL, hidden.connection(), "APP");

        TaskExecutionException hiddenFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-gipk-hidden.sql",
                        "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext()));
        assertTrue(hiddenFailure.getMessage().contains("show_gipk_in_create_table_and_information_schema=ON"));
        assertTrue(hidden.events.contains("gipk-visibility"));
        assertFalse(hidden.events.contains("lock"));
        assertFalse(hidden.events.contains("dml"));

        MysqlPreflightConnection unreadable = new MysqlPreflightConnection(physical, "InnoDB");
        unreadable.productVersion = "8.0.30";
        unreadable.failGipkVisibilityQuery = true;
        useConnection(MYSQL, unreadable.connection(), "APP");

        TaskExecutionException unreadableFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-gipk-unreadable.sql",
                        "INSERT INTO TARGET_ROWS VALUES (2, 'two');\n")), new RecordingContext()));
        assertTrue(unreadableFailure.getMessage().contains(
                "show_gipk_in_create_table_and_information_schema=ON"));
        assertTrue(unreadable.events.contains("gipk-visibility"));
        assertFalse(unreadable.events.contains("lock"));
        assertFalse(unreadable.events.contains("dml"));
        assertEquals(0L, committedRowCount());
    }

    @Test
    void mariaDbAndPre8030MysqlDoNotReadUnsupportedGipkVariable() throws Exception {
        installPlugin(MYSQL);
        MysqlPreflightConnection oldMysql = new MysqlPreflightConnection(physical, "InnoDB");
        oldMysql.productVersion = "8.0.29";
        oldMysql.failGipkVisibilityQuery = true;
        useConnection(MYSQL, oldMysql.connection(), "APP");

        new SQLImporter().run(spec(sql("mysql-pre-gipk.sql",
                "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext());
        assertFalse(oldMysql.events.contains("gipk-visibility"));
        assertTrue(oldMysql.events.contains("dml"));

        installPlugin(MARIADB);
        MysqlPreflightConnection mariaDb = new MysqlPreflightConnection(physical, "InnoDB");
        mariaDb.productName = "MariaDB";
        mariaDb.productMajorVersion = 10;
        mariaDb.productMinorVersion = 11;
        mariaDb.productVersion = "10.11.6-MariaDB";
        mariaDb.failGipkVisibilityQuery = true;
        useConnection(MARIADB, mariaDb.connection(), "APP");

        new SQLImporter().run(spec(sql("mariadb-no-gipk-variable.sql",
                "INSERT INTO TARGET_ROWS VALUES (2, 'two');\n")), new RecordingContext());
        assertFalse(mariaDb.events.contains("gipk-visibility"));
        assertTrue(mariaDb.events.contains("dml"));
        assertEquals(2L, committedRowCount());
    }

    @Test
    void mysqlUnknownProductOrPatchVersionFailsClosedBeforeDml() throws Exception {
        installPlugin(MYSQL);
        MysqlPreflightConnection unknownProduct = new MysqlPreflightConnection(physical, "InnoDB");
        unknownProduct.productName = "Unknown SQL proxy";
        useConnection(MYSQL, unknownProduct.connection(), "APP");

        TaskExecutionException productFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-unknown-product.sql",
                        "INSERT INTO TARGET_ROWS VALUES (1, 'one');\n")), new RecordingContext()));
        assertTrue(productFailure.getMessage().contains("Could not prove whether"));
        assertFalse(unknownProduct.events.contains("lock"));
        assertFalse(unknownProduct.events.contains("dml"));

        MysqlPreflightConnection unknownPatch = new MysqlPreflightConnection(physical, "InnoDB");
        unknownPatch.productVersion = "8.0";
        useConnection(MYSQL, unknownPatch.connection(), "APP");

        TaskExecutionException versionFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec(sql("mysql-unknown-version.sql",
                        "INSERT INTO TARGET_ROWS VALUES (2, 'two');\n")), new RecordingContext()));
        assertTrue(versionFailure.getMessage().contains("patch version"));
        assertFalse(unknownPatch.events.contains("lock"));
        assertFalse(unknownPatch.events.contains("dml"));
        assertEquals(0L, committedRowCount());
    }

    @Test
    void verifiesActiveNamespacesOnTheSameConnectionBeforeDml() throws Exception {
        assertNamespaceAllowed(POSTGRESQL, "PGADMIN",
                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (1, 'one');\n",
                "APPDB", "APP_SCHEMA", "current_database()");
        assertNamespaceAllowed(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (1, N'one');\nGO\n",
                "APPDB", "APP_SCHEMA", "DB_NAME()");
        assertNamespaceAllowed(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (1, 'one');\n/\n",
                "APPDB", "APP_SCHEMA", "SYS_CONTEXT('USERENV','DB_NAME')");
    }

    @Test
    void rejectsMismatchedNullOrUnreadableActiveNamespacesBeforeDml() throws Exception {
        installPlugin(POSTGRESQL);
        NamespaceConnection mismatch = new NamespaceConnection(physical, "OTHER_DB", "APP_SCHEMA", false);
        useConnection(POSTGRESQL, mismatch.connection(), "APPDB");
        TaskExecutionException mismatchFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(sql("postgres-mismatch.sql",
                                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (1, 'one');\n"),
                        "PGADMIN", "APPDB", "APP_SCHEMA"), new RecordingContext()));
        assertTrue(mismatchFailure.getMessage().contains("databases do not match"));
        assertFalse(mismatch.events.contains("dml"));

        installPlugin(SQLSERVER);
        NamespaceConnection missing = new NamespaceConnection(physical, "APPDB", null, false);
        useConnection(SQLSERVER, missing.connection(), "APPDB");
        TaskExecutionException missingFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(sql("sqlserver-null.sql",
                                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (1, N'one');\nGO\n"),
                        "SSMS", "APPDB", "APP_SCHEMA"), new RecordingContext()));
        assertTrue(missingFailure.getMessage().contains("Could not prove the active SQL Server"));
        assertFalse(missing.events.contains("dml"));

        installPlugin(ORACLE);
        NamespaceConnection unreadable = new NamespaceConnection(physical, "APPDB", "APP_SCHEMA", true);
        useConnection(ORACLE, unreadable.connection(), "APPDB");
        TaskExecutionException queryFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(sql("oracle-error.sql",
                                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (1, 'one');\n/\n"),
                        "ORACLE_SQL_DEVELOPER", "APPDB", "APP_SCHEMA"), new RecordingContext()));
        assertTrue(queryFailure.getMessage().contains("Could not verify transactional SQL import targets"));
        assertFalse(unreadable.events.contains("dml"));
    }

    @Test
    void rejectsNonBaseObjectsAndDatabaseSideEffectsBeforeDml() throws Exception {
        assertUnsafeTargetMetadata(POSTGRESQL, "PGADMIN",
                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (1, 'one');\n",
                target -> target.objectType = "v", "not a local base table");
        assertUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (1, N'one');\nGO\n",
                target -> target.objectType = "SN", "not a local base table");
        assertUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (1, 'one');\n/\n",
                target -> target.objectType = "VIEW", "not a local base table");

        assertUnsafeTargetMetadata(POSTGRESQL, "PGADMIN",
                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (2, 'two');\n",
                target -> target.triggerCount = 1, "enabled trigger");
        assertUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (2, N'two');\nGO\n",
                target -> target.triggerCount = 1, "enabled trigger");
        assertUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (2, 'two');\n/\n",
                target -> target.triggerCount = 1, "enabled trigger");

        assertUnsafeTargetMetadata(POSTGRESQL, "PGADMIN",
                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (3, 'three');\n",
                target -> target.rewriteRuleCount = 1, "rewrite rule");
        assertUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (3, N'three');\nGO\n",
                target -> target.generatedColumnCount = 1, "generated");
        assertUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (3, 'three');\n/\n",
                target -> target.generatedColumnCount = 1, "generated");
    }

    @Test
    void rejectsRowSecurityAndCrossObjectSideEffectsAfterLockBeforeDml() throws Exception {
        assertLockedUnsafeTargetMetadata(POSTGRESQL, "PGADMIN",
                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (10, 'rls');\n",
                target -> target.postgresRowSecurity = true, "row-level security");
        assertLockedUnsafeTargetMetadata(POSTGRESQL, "PGADMIN",
                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (11, 'forced rls');\n",
                target -> target.postgresForceRowSecurity = true, "row-level security");

        assertLockedUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (10, N'indexed view');\nGO\n",
                target -> target.sqlServerIndexedViewDependency = true, "indexed view");
        assertLockedUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (11, N'cdc');\nGO\n",
                target -> target.sqlServerCdc = true, "CDC");
        assertLockedUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (12, N'change tracking');\nGO\n",
                target -> target.sqlServerChangeTracking = true, "change tracking");

        assertLockedUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (10, 'mv log');\n/\n",
                target -> target.oracleMviewLog = true, "materialized view log");
        assertLockedUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (11, 'commit mv');\n/\n",
                target -> target.oracleCommitMview = true, "COMMIT/STATEMENT");
        assertLockedUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (12, 'statement mv');\n/\n",
                target -> target.oracleStatementMview = true, "COMMIT/STATEMENT");
    }

    @Test
    void rejectsUnprovenSqlServerAndOracleMetadataVisibilityAfterLockBeforeDml() throws Exception {
        assertLockedUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (20, N'invisible');\nGO\n",
                target -> target.metadataVisible = false, "sysadmin-scoped SQL Server catalog");
        assertLockedUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (20, 'invisible');\n/\n",
                target -> target.metadataVisible = false, "Oracle dictionary");
    }

    @Test
    void rejectsSqlServerAndOracleImplicitWriteColumnsBeforeDml() throws Exception {
        assertUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (4, N'four');\nGO\n",
                target -> target.sqlServerRowversion = true, "generated");
        assertUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (5, N'five');\nGO\n",
                target -> target.sqlServerHiddenColumn = true, "generated");
        assertUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (6, N'six');\nGO\n",
                target -> target.sqlServerGeneratedAlways = true, "generated");
        assertUnsafeTargetMetadata(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (7, N'seven');\nGO\n",
                target -> target.sqlServerColumnSet = true, "generated");
        assertUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (4, 'four');\n/\n",
                target -> {
                    target.oracleHiddenColumn = true;
                    target.generatedColumnCount = 1;
                }, "generated");
        assertUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (5, NULL);\n/\n",
                target -> target.oracleDefaultOnNull = true, "generated");
    }

    @Test
    void rejectsPostgresAndOracleNonpersistentTargetsBeforeDml() throws Exception {
        assertUnsafeTargetMetadata(POSTGRESQL, "PGADMIN",
                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (7, 'temp');\n",
                target -> target.postgresPersistence = "t", "nonpersistent");
        assertUnsafeTargetMetadata(POSTGRESQL, "PGADMIN",
                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (8, 'unlogged');\n",
                target -> target.postgresPersistence = "u", "nonpersistent");
        assertUnsafeTargetMetadata(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (6, 'temporary');\n/\n",
                target -> target.oracleTemporaryTable = true, "nonpersistent");
    }

    @Test
    void rejectsTargetReplacementAndUnreadableMetadataBeforeDml() throws Exception {
        NamespaceConnection replaced = namespaceConnection(POSTGRESQL);
        replaced.objectIdAfterLock = "202";
        useConnection(POSTGRESQL, replaced.connection(), "APPDB");
        TaskExecutionException replacementFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(sql("postgres-replaced.sql",
                                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\", \"NAME\") VALUES (1, 'one');\n"),
                        "PGADMIN", "APPDB", "APP_SCHEMA"), new RecordingContext()));
        assertTrue(replacementFailure.getMessage().contains("changed during preflight"));
        assertFalse(replaced.events.contains("dml"));

        NamespaceConnection unreadable = namespaceConnection(SQLSERVER);
        unreadable.failMetadataQuery = true;
        useConnection(SQLSERVER, unreadable.connection(), "APPDB");
        TaskExecutionException metadataFailure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(sql("sqlserver-metadata-error.sql",
                                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID], [NAME]) VALUES (1, N'one');\nGO\n"),
                        "SSMS", "APPDB", "APP_SCHEMA"), new RecordingContext()));
        assertTrue(metadataFailure.getMessage().contains("Could not verify transactional SQL import targets"));
        assertFalse(unreadable.events.contains("lock"));
        assertFalse(unreadable.events.contains("dml"));
    }

    @Test
    void metadataAndLocksUseTheDialectCanonicalIdentifier() throws Exception {
        assertCanonicalTarget(POSTGRESQL, "PGADMIN", "App_Schema.Target_Rows",
                "app_schema", "target_rows");
        assertCanonicalTarget(POSTGRESQL, "PGADMIN", "\"App_Schema\".\"Target_Rows\"",
                "App_Schema", "Target_Rows");
        assertCanonicalTarget(ORACLE, "ORACLE_SQL_DEVELOPER", "App_Schema.Target_Rows",
                "APP_SCHEMA", "TARGET_ROWS");
        assertCanonicalTarget(ORACLE, "ORACLE_SQL_DEVELOPER", "\"App_Schema\".\"Target_Rows\"",
                "App_Schema", "Target_Rows");

        NamespaceConnection distinctQuotedTarget = new NamespaceConnection(
                physical, "APPDB", "APP_SCHEMA", false);
        useConnection(POSTGRESQL, distinctQuotedTarget.connection(), "APPDB");
        TaskExecutionException mismatch = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(sql("postgres-unquoted-distinct.sql",
                                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID, NAME) VALUES (1, 'one');\n"),
                        "PGADMIN", "APPDB", "APP_SCHEMA"), new RecordingContext()));
        assertTrue(mismatch.getMessage().contains("outside the selected TABLE import target"));
        assertFalse(distinctQuotedTarget.events.contains("metadata"));
        assertFalse(distinctQuotedTarget.events.contains("dml"));
    }

    @Test
    void quotedIdentifierWhitespaceIsPreservedThroughScopeMetadataAndLocking() throws Exception {
        installPlugin(POSTGRESQL);
        NamespaceConnection target = new NamespaceConnection(
                physical, " APPDB ", " APP_SCHEMA ", false);
        target.columnNames = List.of(" ID ", " NAME ");
        useConnection(POSTGRESQL, target.connection(), " APPDB ");
        ImportTaskSpec spec = thirdPartySpec(sql("postgres-quoted-space-target.sql", """
                        INSERT INTO \" APP_SCHEMA \".\" TARGET_ROWS \" (\" ID \", \" NAME \")
                        VALUES (1, 'one');
                        """), "PGADMIN", " APPDB ", " APP_SCHEMA ");
        spec.getTarget().setTableName(" TARGET_ROWS ");

        new SQLImporter().run(spec, new RecordingContext());

        assertTrue(target.events.contains("metadata-target: APP_SCHEMA . TARGET_ROWS "),
                () -> target.events.toString());
        assertTrue(target.events.stream().anyMatch(event -> event.equals(
                        "lock-sql:LOCK TABLE \" APP_SCHEMA \".\" TARGET_ROWS \" "
                                + "IN SHARE ROW EXCLUSIVE MODE NOWAIT")),
                () -> target.events.toString());
        assertTrue(target.events.contains("columns"), () -> target.events.toString());
        assertTrue(target.events.contains("dml"), () -> target.events.toString());
    }

    @Test
    void requiresStrictInsertColumnsToMatchEveryLockedTargetColumn() throws Exception {
        assertColumnSetRejected(POSTGRESQL, "PGADMIN",
                "INSERT INTO \"APP_SCHEMA\".\"TARGET_ROWS\" (\"ID\") VALUES (1);\n");
        assertColumnSetRejected(SQLSERVER, "SSMS",
                "INSERT INTO [APPDB].[APP_SCHEMA].[TARGET_ROWS] ([ID]) VALUES (1);\nGO\n");
        assertColumnSetRejected(ORACLE, "ORACLE_SQL_DEVELOPER",
                "INSERT INTO APP_SCHEMA.TARGET_ROWS (ID) VALUES (1);\n/\n");

        installPlugin(MYSQL);
        MysqlPreflightConnection mysql = new MysqlPreflightConnection(physical, "InnoDB");
        useConnection(MYSQL, mysql.connection(), "APP");
        new SQLImporter().run(thirdPartySpec(sql("mysql-reordered-columns.sql",
                        "INSERT INTO TARGET_ROWS (NAME, ID) VALUES ('one', 1);\n"),
                "NAVICAT", "APP", null), new RecordingContext());
        assertTrue(mysql.events.contains("columns"), () -> mysql.events.toString());
        assertTrue(mysql.events.contains("dml"), () -> mysql.events.toString());

        MysqlPreflightConnection missing = new MysqlPreflightConnection(physical, "InnoDB");
        useConnection(MYSQL, missing.connection(), "APP");
        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(sql("mysql-missing-column.sql",
                                "INSERT INTO TARGET_ROWS (ID) VALUES (2);\n"),
                        "NAVICAT", "APP", null), new RecordingContext()));
        assertTrue(failure.getMessage().contains("does not exactly match"), failure::getMessage);
        assertTrue(missing.events.contains("columns"), () -> missing.events.toString());
        assertFalse(missing.events.contains("dml"), () -> missing.events.toString());
    }

    @Test
    void acquiresMultiTargetLocksInCanonicalOrderWithBoundedWait() throws Exception {
        installPlugin(SQLSERVER);
        NamespaceConnection target = new NamespaceConnection(physical, "APPDB", "APP_SCHEMA", false);
        target.failLockNumber = 2;
        useConnection(SQLSERVER, target.connection(), "APPDB");
        ImportTaskSpec spec = thirdPartySpec(sql("sqlserver-resolved-lock-order.sql", """
                        INSERT INTO [Z_ROWS] ([ID], [NAME]) VALUES (1, N'z');
                        GO
                        INSERT INTO [APP_SCHEMA].[A_ROWS] ([ID], [NAME]) VALUES (2, N'a');
                        GO
                        """), "SSMS", "APPDB", null);
        spec.setScope(ImportScope.DATABASE);
        spec.getTarget().setTableName(null);

        assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(spec, new RecordingContext()));

        List<String> locks = target.events.stream()
                .filter(event -> event.startsWith("lock-sql:"))
                .toList();
        assertEquals(2, locks.size(), () -> target.events.toString());
        assertTrue(locks.get(0).contains("A_ROWS"), () -> locks.toString());
        assertTrue(locks.get(1).contains("Z_ROWS"), () -> locks.toString());
        assertTrue(locks.stream().allMatch(lock -> lock.contains("TABLOCKX, HOLDLOCK")),
                () -> locks.toString());
        assertEquals(2, target.events.stream().filter("lock-timeout:5"::equals).count(),
                () -> target.events.toString());
        assertFalse(target.events.contains("dml"), () -> target.events.toString());
    }

    private void installPlugin(String databaseType) {
        if (!previousPlugins.containsKey(databaseType)) {
            previousPlugins.put(databaseType, Chat2DBContext.PLUGIN_MAP.put(databaseType, new H2Plugin()));
        }
    }

    private void useConnection(String databaseType, Connection connection, String databaseName) {
        connectInfo = new ConnectInfo();
        connectInfo.setDbType(databaseType);
        connectInfo.setDatabaseName(databaseName);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        Chat2DBContext.putContext(connectInfo);
    }

    private void assertNamespaceAllowed(String databaseType, String profile, String statement,
            String database, String schema, String expectedQueryFragment) throws Exception {
        installPlugin(databaseType);
        NamespaceConnection namespace = new NamespaceConnection(physical, database, schema, false);
        useConnection(databaseType, namespace.connection(), database);

        new SQLImporter().run(thirdPartySpec(
                sql(databaseType.toLowerCase() + "-namespace.sql", statement), profile, database, schema),
                new RecordingContext());

        int namespaceQuery = namespace.events.indexOf("namespace:" + expectedQueryFragment);
        int firstMetadata = namespace.events.indexOf("metadata");
        int lock = namespace.events.indexOf("lock");
        int secondMetadata = namespace.events.lastIndexOf("metadata");
        int dml = namespace.events.indexOf("dml");
        assertTrue(namespaceQuery >= 0 && firstMetadata > namespaceQuery && lock > firstMetadata
                && secondMetadata > lock && dml > secondMetadata, () -> namespace.events.toString());
    }

    private void assertUnsafeTargetMetadata(String databaseType, String profile, String statement,
            java.util.function.Consumer<NamespaceConnection> configure, String expectedMessage) throws Exception {
        NamespaceConnection target = namespaceConnection(databaseType);
        configure.accept(target);
        useConnection(databaseType, target.connection(), "APPDB");

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(
                        sql(databaseType.toLowerCase() + "-unsafe-target-" + UUID.randomUUID() + ".sql",
                                statement),
                        profile, "APPDB", "APP_SCHEMA"), new RecordingContext()));

        assertTrue(failure.getMessage().contains(expectedMessage), failure::getMessage);
        assertFalse(target.events.contains("lock"));
        assertFalse(target.events.contains("dml"));
    }

    private void assertLockedUnsafeTargetMetadata(String databaseType, String profile, String statement,
            java.util.function.Consumer<NamespaceConnection> configure, String expectedMessage) throws Exception {
        NamespaceConnection target = namespaceConnection(databaseType);
        configure.accept(target);
        useConnection(databaseType, target.connection(), "APPDB");

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(
                        sql(databaseType.toLowerCase() + "-locked-side-effect-" + UUID.randomUUID() + ".sql",
                                statement),
                        profile, "APPDB", "APP_SCHEMA"), new RecordingContext()));

        assertTrue(failure.getMessage().contains(expectedMessage), failure::getMessage);
        assertTrue(target.events.contains("lock"), () -> target.events.toString());
        assertFalse(target.events.contains("dml"), () -> target.events.toString());
    }

    private void assertColumnSetRejected(String databaseType, String profile, String statement) throws Exception {
        NamespaceConnection target = namespaceConnection(databaseType);
        useConnection(databaseType, target.connection(), "APPDB");

        TaskExecutionException failure = assertThrows(TaskExecutionException.class,
                () -> new SQLImporter().run(thirdPartySpec(
                                sql(databaseType.toLowerCase() + "-column-set-" + UUID.randomUUID() + ".sql",
                                        statement),
                                profile, "APPDB", "APP_SCHEMA"),
                        new RecordingContext()));

        assertTrue(failure.getMessage().contains("does not exactly match"), failure::getMessage);
        assertTrue(target.events.contains("lock"), () -> target.events.toString());
        assertTrue(target.events.contains("columns"), () -> target.events.toString());
        assertFalse(target.events.contains("dml"), () -> target.events.toString());
    }

    private NamespaceConnection namespaceConnection(String databaseType) {
        installPlugin(databaseType);
        return new NamespaceConnection(physical, "APPDB", "APP_SCHEMA", false);
    }

    private void assertCanonicalTarget(String databaseType, String profile, String sqlTarget,
            String expectedSchema, String expectedTable) throws Exception {
        installPlugin(databaseType);
        NamespaceConnection target = new NamespaceConnection(
                physical, "APPDB", expectedSchema, false);
        if (POSTGRESQL.equals(databaseType)) {
            target.columnNames = List.of("id", "name");
        }
        useConnection(databaseType, target.connection(), "APPDB");

        ImportTaskSpec spec = thirdPartySpec(sql(
                        databaseType.toLowerCase() + "-canonical-" + Math.abs(sqlTarget.hashCode()) + ".sql",
                        "INSERT INTO " + sqlTarget + " (ID, NAME) VALUES (1, 'one');\n"),
                profile, "APPDB", expectedSchema);
        spec.getTarget().setTableName(expectedTable);
        new SQLImporter().run(spec, new RecordingContext());

        assertTrue(target.events.contains("metadata-target:" + expectedSchema + "." + expectedTable),
                () -> target.events.toString());
        assertTrue(target.events.stream().anyMatch(event -> event.startsWith("lock-sql:")
                        && event.contains(expectedSchema) && event.contains(expectedTable)),
                () -> target.events.toString());
        assertTrue(target.events.contains("dml"), () -> target.events.toString());
    }

    private ImportTaskSpec spec(Path source) {
        return ImportTaskSpec.builder()
                .sourceFile(source.toString())
                .format("SQL")
                .scope(ImportScope.TABLE)
                .target(TaskTargetSnapshot.builder().databaseName("APP").tableName("TARGET_ROWS").build())
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .sqlExporterProfile("NAVICAT")
                        .build())
                .build();
    }

    private ImportTaskSpec thirdPartySpec(Path source, String profile, String database, String schema) {
        return ImportTaskSpec.builder()
                .sourceFile(source.toString())
                .format("SQL")
                .sourceKind("THIRD_PARTY")
                .scope(ImportScope.TABLE)
                .target(TaskTargetSnapshot.builder()
                        .databaseName(database)
                        .schemaName(schema)
                        .tableName("TARGET_ROWS")
                        .build())
                .options(ImportOptions.builder()
                        .charset("UTF-8")
                        .sqlExporterProfile(profile)
                        .build())
                .build();
    }

    private Path sql(String name, String contents) throws Exception {
        return Files.writeString(tempDirectory.resolve(name), contents, StandardCharsets.UTF_8);
    }

    private long committedRowCount() throws Exception {
        try (Connection query = DriverManager.getConnection(databaseUrl, "sa", "");
             Statement statement = query.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM TARGET_ROWS")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class ConnectionFaults implements InvocationHandler {

        private final Connection delegate;
        private boolean failTransactionStart;
        private boolean failCommit;
        private boolean failRestoreAfterCommit;
        private boolean committed;
        private int transactionStartCalls;
        private int commitCalls;
        private int rollbackCalls;
        private Runnable beforeCommit = () -> { };

        private ConnectionFaults(Connection delegate) {
            this.delegate = delegate;
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if ("setAutoCommit".equals(method.getName())) {
                boolean requested = (Boolean) arguments[0];
                if (!requested) {
                    transactionStartCalls++;
                }
                if (!requested && failTransactionStart) {
                    throw new SQLException("injected transaction start failure");
                }
                if (requested && committed && failRestoreAfterCommit) {
                    throw new SQLException("injected restore failure");
                }
            } else if ("commit".equals(method.getName())) {
                commitCalls++;
                beforeCommit.run();
                if (failCommit) {
                    throw new SQLException("injected commit failure");
                }
                Object result = SQLImporterConnectionSafetyTest.invoke(delegate, method, arguments);
                committed = true;
                return result;
            } else if ("rollback".equals(method.getName())) {
                rollbackCalls++;
            }
            return SQLImporterConnectionSafetyTest.invoke(delegate, method, arguments);
        }
    }

    private static final class MysqlPreflightConnection implements InvocationHandler {

        private final Connection delegate;
        private final String engine;
        private final int triggerCount;
        private final List<String> grants;
        private final List<String> events = new ArrayList<>();
        private String tableType = "BASE TABLE";
        private int generatedColumnCount;
        private boolean temporaryTableShadowsTarget;
        private String productName = "MySQL";
        private int productMajorVersion = 8;
        private int productMinorVersion;
        private String productVersion = "8.0.29";
        private String gipkVisibility = "ON";
        private boolean failGipkVisibilityQuery;
        private List<String> columnNames = List.of("ID", "NAME");

        private MysqlPreflightConnection(Connection delegate, String engine) {
            this(delegate, engine, 0, "GRANT TRIGGER ON `APP`.* TO 'importer'@'%'");
        }

        private MysqlPreflightConnection(Connection delegate, String engine, int triggerCount, String grant) {
            this(delegate, engine, triggerCount, List.of(grant));
        }

        private MysqlPreflightConnection(Connection delegate, String engine, int triggerCount, List<String> grants) {
            this.delegate = delegate;
            this.engine = engine;
            this.triggerCount = triggerCount;
            this.grants = List.copyOf(grants);
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if ("getCatalog".equals(method.getName())) {
                return "APP";
            }
            if ("getMetaData".equals(method.getName())) {
                return databaseMetaData();
            }
            if ("prepareStatement".equals(method.getName()) && arguments != null
                    && arguments.length > 0 && arguments[0] instanceof String sql) {
                if (sql.startsWith("SELECT 1 FROM")) {
                    events.add("lock");
                    return preparedStatement(QueryResult.EMPTY);
                }
                if (sql.startsWith("SHOW CREATE TABLE")) {
                    events.add("show-create");
                    return preparedStatement(QueryResult.SHOW_CREATE);
                }
                if (sql.startsWith("SELECT t.TABLE_TYPE, t.ENGINE")) {
                    events.add("engine");
                    return preparedStatement(QueryResult.METADATA);
                }
                if (sql.startsWith("SHOW GRANTS FOR CURRENT_USER")) {
                    events.add("grants");
                    return preparedStatement(QueryResult.GRANT);
                }
                if (sql.startsWith("SELECT COUNT(*) FROM information_schema.TRIGGERS")) {
                    events.add("triggers");
                    return preparedStatement(QueryResult.TRIGGER_COUNT);
                }
                if (sql.contains("@@SESSION.show_gipk_in_create_table_and_information_schema")) {
                    events.add("gipk-visibility");
                    return preparedStatement(QueryResult.GIPK_VISIBILITY);
                }
                if (sql.startsWith("SELECT c.COLUMN_NAME FROM information_schema.COLUMNS")) {
                    events.add("columns");
                    return preparedStatement(QueryResult.COLUMNS);
                }
            }
            if ("createStatement".equals(method.getName())) {
                Statement statement = (Statement) SQLImporterConnectionSafetyTest.invoke(
                        delegate, method, arguments);
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                        new Class<?>[]{Statement.class}, (statementProxy, statementMethod, statementArguments) -> {
                            if ("executeBatch".equals(statementMethod.getName())) {
                                events.add("dml");
                            }
                            return SQLImporterConnectionSafetyTest.invoke(
                                    statement, statementMethod, statementArguments);
                        });
            }
            return SQLImporterConnectionSafetyTest.invoke(delegate, method, arguments);
        }

        private PreparedStatement preparedStatement(QueryResult queryResult) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (queryResult == QueryResult.EMPTY && "setQueryTimeout".equals(method.getName())) {
                            events.add("lock-timeout:" + arguments[0]);
                            return null;
                        }
                        if ("executeQuery".equals(method.getName())) {
                            if (queryResult == QueryResult.GIPK_VISIBILITY && failGipkVisibilityQuery) {
                                throw new SQLException("injected unknown system variable");
                            }
                            return queryResult == QueryResult.COLUMNS
                                    ? columnResultSet(columnNames) : resultSet(queryResult);
                        }
                        if ("close".equals(method.getName()) || method.getName().startsWith("set")) {
                            return null;
                        }
                        if ("isClosed".equals(method.getName())) {
                            return false;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private ResultSet resultSet(QueryResult queryResult) {
            AtomicBoolean beforeFirst = new AtomicBoolean(true);
            AtomicInteger grantIndex = new AtomicInteger(-1);
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                        if ("next".equals(method.getName())) {
                            if (queryResult == QueryResult.GRANT) {
                                return grantIndex.incrementAndGet() < grants.size();
                            }
                            return queryResult != QueryResult.EMPTY && beforeFirst.getAndSet(false);
                        }
                        if ("getString".equals(method.getName())) {
                            if (queryResult == QueryResult.GRANT) {
                                return grants.get(grantIndex.get());
                            }
                            if (queryResult == QueryResult.GIPK_VISIBILITY) {
                                return gipkVisibility;
                            }
                            int column = (Integer) arguments[0];
                            if (queryResult == QueryResult.SHOW_CREATE) {
                                return column == 1 ? "TARGET_ROWS"
                                        : temporaryTableShadowsTarget
                                        ? "CREATE TEMPORARY TABLE `TARGET_ROWS` (`ID` int)"
                                        : "CREATE TABLE `TARGET_ROWS` (`ID` int) ENGINE=InnoDB";
                            }
                            return column == 1 ? tableType : engine;
                        }
                        if ("getLong".equals(method.getName())) {
                            return queryResult == QueryResult.METADATA
                                    ? (long) generatedColumnCount : (long) triggerCount;
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private enum QueryResult {
            EMPTY,
            METADATA,
            GRANT,
            TRIGGER_COUNT,
            SHOW_CREATE,
            GIPK_VISIBILITY,
            COLUMNS
        }

        private ResultSet columnResultSet(List<String> columns) {
            AtomicInteger index = new AtomicInteger(-1);
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                        if ("next".equals(method.getName())) {
                            return index.incrementAndGet() < columns.size();
                        }
                        if ("getString".equals(method.getName())) {
                            return columns.get(index.get());
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private DatabaseMetaData databaseMetaData() throws SQLException {
            DatabaseMetaData metadata = delegate.getMetaData();
            return (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "getDatabaseProductName" -> productName;
                            case "getDatabaseMajorVersion" -> productMajorVersion;
                            case "getDatabaseMinorVersion" -> productMinorVersion;
                            case "getDatabaseProductVersion" -> productVersion;
                            default -> SQLImporterConnectionSafetyTest.invoke(metadata, method, arguments);
                        };
                    });
        }
    }

    private static final class NamespaceConnection implements InvocationHandler {

        private final Connection delegate;
        private final String database;
        private final String schema;
        private final boolean failNamespaceQuery;
        private final List<String> events = new ArrayList<>();
        private String objectType;
        private String objectIdBeforeLock = "101";
        private String objectIdAfterLock = "101";
        private int triggerCount;
        private int rewriteRuleCount;
        private int generatedColumnCount;
        private boolean sqlServerRowversion;
        private boolean sqlServerHiddenColumn;
        private boolean sqlServerGeneratedAlways;
        private boolean sqlServerColumnSet;
        private boolean oracleHiddenColumn;
        private boolean oracleDefaultOnNull;
        private String postgresPersistence = "p";
        private boolean oracleTemporaryTable;
        private boolean postgresRowSecurity;
        private boolean postgresForceRowSecurity;
        private boolean sqlServerIndexedViewDependency;
        private boolean sqlServerCdc;
        private boolean sqlServerChangeTracking;
        private boolean oracleMviewLog;
        private boolean oracleCommitMview;
        private boolean oracleStatementMview;
        private boolean metadataVisible = true;
        private boolean failMetadataQuery;
        private int metadataQueryCount;
        private List<String> columnNames = List.of("ID", "NAME");
        private int failLockNumber;
        private int lockCount;

        private NamespaceConnection(Connection delegate, String database, String schema,
                boolean failNamespaceQuery) {
            this.delegate = delegate;
            this.database = database;
            this.schema = schema;
            this.failNamespaceQuery = failNamespaceQuery;
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if ("prepareStatement".equals(method.getName()) && arguments != null
                    && arguments.length > 0 && arguments[0] instanceof String sql) {
                if (sql.startsWith("SELECT current_database()") || sql.startsWith("SELECT DB_NAME()")
                        || sql.startsWith("SELECT SYS_CONTEXT('USERENV','DB_NAME')")) {
                    String marker = sql.contains("current_database()") ? "current_database()"
                            : sql.contains("DB_NAME()") ? "DB_NAME()" : "SYS_CONTEXT('USERENV','DB_NAME')";
                    events.add("namespace:" + marker);
                    return namespaceStatement();
                }
                if (sql.startsWith("SELECT a.attname FROM pg_catalog.pg_attribute")
                        || sql.startsWith("SELECT c.name FROM sys.columns")
                        || sql.startsWith("SELECT c.column_name FROM dba_tab_cols")) {
                    events.add("columns");
                    return targetColumnsStatement();
                }
                if (sql.contains("pg_catalog.pg_class") || sql.contains("FROM sys.objects")
                        || sql.contains("FROM dba_objects")) {
                    events.add("metadata");
                    int queryNumber = metadataQueryCount++;
                    return targetMetadataStatement(sql, queryNumber == 0
                            ? objectIdBeforeLock : objectIdAfterLock);
                }
                if (sql.startsWith("LOCK TABLE ") || sql.startsWith("SELECT TOP (1) 1 FROM ")) {
                    events.add("lock");
                    events.add("lock-sql:" + sql);
                    return controlStatement(++lockCount);
                }
            }
            if ("createStatement".equals(method.getName())) {
                Statement statement = (Statement) SQLImporterConnectionSafetyTest.invoke(
                        delegate, method, arguments);
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                        new Class<?>[]{Statement.class}, (statementProxy, statementMethod, statementArguments) -> {
                            if ("addBatch".equals(statementMethod.getName())) {
                                return null;
                            }
                            if ("executeBatch".equals(statementMethod.getName())) {
                                events.add("dml");
                                return new int[]{1};
                            }
                            return SQLImporterConnectionSafetyTest.invoke(
                                    statement, statementMethod, statementArguments);
                        });
            }
            return SQLImporterConnectionSafetyTest.invoke(delegate, method, arguments);
        }

        private PreparedStatement namespaceStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if ("executeQuery".equals(method.getName())) {
                            if (failNamespaceQuery) {
                                throw new SQLException("injected namespace query failure");
                            }
                            return namespaceResultSet();
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        if ("isClosed".equals(method.getName())) {
                            return false;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement targetMetadataStatement(String sql, String objectId) {
            String[] parameters = new String[2];
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if ("executeQuery".equals(method.getName())) {
                            if (failMetadataQuery) {
                                throw new SQLException("injected target metadata query failure");
                            }
                            events.add("metadata-target:" + parameters[0] + "." + parameters[1]);
                            return targetMetadataResultSet(sql, objectId);
                        }
                        if ("setString".equals(method.getName())) {
                            parameters[(Integer) arguments[0] - 1] = (String) arguments[1];
                            return null;
                        }
                        if ("close".equals(method.getName()) || method.getName().startsWith("set")) {
                            return null;
                        }
                        if ("isClosed".equals(method.getName())) {
                            return false;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement controlStatement(int lockNumber) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if ("setQueryTimeout".equals(method.getName())) {
                            events.add("lock-timeout:" + arguments[0]);
                            return null;
                        }
                        if ("execute".equals(method.getName())) {
                            if (failLockNumber == lockNumber) {
                                throw new SQLException("injected lock failure " + lockNumber);
                            }
                            return false;
                        }
                        if ("close".equals(method.getName()) || method.getName().startsWith("set")) {
                            return false;
                        }
                        if ("isClosed".equals(method.getName())) {
                            return false;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement targetColumnsStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if ("executeQuery".equals(method.getName())) {
                            AtomicInteger index = new AtomicInteger(-1);
                            return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                    new Class<?>[]{ResultSet.class}, (resultProxy, resultMethod, resultArguments) -> {
                                        if ("next".equals(resultMethod.getName())) {
                                            return index.incrementAndGet() < columnNames.size();
                                        }
                                        if ("getString".equals(resultMethod.getName())) {
                                            return columnNames.get(index.get());
                                        }
                                        if ("close".equals(resultMethod.getName())) {
                                            return null;
                                        }
                                        return defaultValue(resultMethod.getReturnType());
                                    });
                        }
                        if ("close".equals(method.getName()) || method.getName().startsWith("set")) {
                            return null;
                        }
                        if ("isClosed".equals(method.getName())) {
                            return false;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private ResultSet namespaceResultSet() {
            AtomicBoolean beforeFirst = new AtomicBoolean(true);
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                        if ("next".equals(method.getName())) {
                            return beforeFirst.getAndSet(false);
                        }
                        if ("getString".equals(method.getName())) {
                            int column = (Integer) arguments[0];
                            return column == 1 ? database : schema;
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private ResultSet targetMetadataResultSet(String sql, String objectId) {
            AtomicBoolean beforeFirst = new AtomicBoolean(true);
            String defaultType = sql.contains("pg_catalog.pg_class") ? "r"
                    : sql.contains("FROM sys.objects") ? "U" : "TABLE";
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> {
                        if ("next".equals(method.getName())) {
                            return beforeFirst.getAndSet(false);
                        }
                        if ("getString".equals(method.getName())) {
                            int column = (Integer) arguments[0];
                            return column == 1 ? objectId
                                    : objectType == null ? defaultType : objectType;
                        }
                        if ("getLong".equals(method.getName())) {
                            int column = (Integer) arguments[0];
                            return switch (column) {
                                case 3 -> (long) triggerCount;
                                case 4 -> (long) rewriteRuleCount;
                                case 5 -> implicitWriteColumnCount(sql);
                                case 6 -> nonPersistentCount(sql);
                                case 7 -> sideEffectCount(sql);
                                case 8 -> metadataVisibilityProof(sql);
                                default -> 0L;
                            };
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        private long implicitWriteColumnCount(String sql) {
            if (sql.contains("FROM sys.objects")) {
                long count = generatedColumnCount;
                count += sqlServerRowversion && sql.contains("c.system_type_id=189") ? 1 : 0;
                count += sqlServerHiddenColumn && sql.contains("c.is_hidden=1") ? 1 : 0;
                count += sqlServerGeneratedAlways && sql.contains("c.generated_always_type<>0") ? 1 : 0;
                count += sqlServerColumnSet && sql.contains("c.is_column_set=1") ? 1 : 0;
                return count;
            }
            if (sql.contains("FROM dba_objects")) {
                if (oracleHiddenColumn && sql.contains("NVL(c.hidden_column,'NO')='NO'")) {
                    return 0;
                }
                long count = generatedColumnCount;
                count += oracleHiddenColumn && sql.contains("NVL(c.hidden_column,'NO')='YES'") ? 1 : 0;
                count += oracleDefaultOnNull && sql.contains("NVL(c.default_on_null,'NO')='YES'") ? 1 : 0;
                return count;
            }
            return generatedColumnCount;
        }

        private long nonPersistentCount(String sql) {
            if (sql.contains("pg_catalog.pg_class")) {
                return !"p".equals(postgresPersistence) && sql.contains("c.relpersistence") ? 1L : 0L;
            }
            if (sql.contains("FROM dba_objects")) {
                return oracleTemporaryTable && sql.contains("t.temporary") && sql.contains("t.duration")
                        ? 1L : 0L;
            }
            return 0L;
        }

        private long sideEffectCount(String sql) {
            if (sql.contains("pg_catalog.pg_class")) {
                return (postgresRowSecurity && sql.contains("c.relrowsecurity")
                        || postgresForceRowSecurity && sql.contains("c.relforcerowsecurity")) ? 1L : 0L;
            }
            if (sql.contains("FROM sys.objects")) {
                long count = sqlServerIndexedViewDependency
                        && sql.contains("sys.sql_expression_dependencies")
                        && sql.contains("sys.views") && sql.contains("sys.indexes") ? 1L : 0L;
                count += sqlServerCdc && sql.contains("t.is_tracked_by_cdc") ? 1L : 0L;
                count += sqlServerChangeTracking && sql.contains("sys.change_tracking_tables") ? 1L : 0L;
                return count;
            }
            if (sql.contains("FROM dba_objects")) {
                long count = oracleMviewLog && sql.contains("dba_mview_logs") ? 1L : 0L;
                boolean mviewDependencyQuery = sql.contains("dba_mviews")
                        && sql.contains("dba_mview_detail_relations")
                        && sql.contains("dba_dependencies")
                        && sql.contains("mv.refresh_mode IN ('COMMIT','STATEMENT')");
                count += oracleCommitMview && mviewDependencyQuery ? 1L : 0L;
                count += oracleStatementMview && mviewDependencyQuery ? 1L : 0L;
                return count;
            }
            return 0L;
        }

        private long metadataVisibilityProof(String sql) {
            if (sql.contains("FROM sys.objects")) {
                boolean proofQuery = sql.contains("IS_SRVROLEMEMBER('sysadmin')=1");
                return metadataVisible && proofQuery ? 1L : 0L;
            }
            if (sql.contains("FROM dba_objects")) {
                boolean proofQuery = sql.contains("dba_mview_logs")
                        && sql.contains("dba_mview_detail_relations") && sql.contains("dba_mviews")
                        && sql.contains("dba_dependencies") && sql.contains("dba_triggers")
                        && sql.contains("dba_tab_cols");
                return metadataVisible && proofQuery ? 1L : 0L;
            }
            return 1L;
        }
    }

    private static class RecordingContext implements TaskExecutionContext {

        private final Connection cancellationConnection;
        private final boolean cancelAfterDml;
        private final List<String> warningCodes = new ArrayList<>();

        private RecordingContext() {
            this(null, false);
        }

        private RecordingContext(Connection cancellationConnection, boolean cancelAfterDml) {
            this.cancellationConnection = cancellationConnection;
            this.cancelAfterDml = cancelAfterDml;
        }

        @Override
        public void reportProgress(int progress, String stage, String message) {
        }

        @Override
        public void logInfo(String code, String message) {
        }

        @Override
        public void logInfo(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void logWarn(String code, String message, Map<String, Object> details) {
            warningCodes.add(code);
        }

        @Override
        public void logError(String code, String message, Map<String, Object> details) {
        }

        @Override
        public void checkCancelled() {
            if (!cancelAfterDml || cancellationConnection == null) {
                return;
            }
            try {
                if (cancellationConnection.getAutoCommit()) {
                    return;
                }
                try (Statement statement = cancellationConnection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM TARGET_ROWS")) {
                    if (rows.next() && rows.getLong(1) > 0) {
                        throw new TaskCancelledException();
                    }
                }
            } catch (TaskCancelledException cancelled) {
                throw cancelled;
            } catch (SQLException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public void enterCommitPhase() {
            checkCancelled();
        }

        @Override
        public void registerCancelable(TaskCancelable resource) {
        }

        @Override
        public ArtifactDraft createArtifact(String outputDirectory, String fileName, String mediaType) {
            throw new UnsupportedOperationException("SQL import does not create artifacts");
        }

        @Override
        public void write(String content) {
        }

        @Override
        public void onStatementCreated(Statement statement) {
        }

        @Override
        public void onStatementClosed(Statement statement) {
        }
    }
}
