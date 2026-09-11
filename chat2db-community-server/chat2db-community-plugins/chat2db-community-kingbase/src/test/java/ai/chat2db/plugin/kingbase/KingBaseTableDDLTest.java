package ai.chat2db.plugin.kingbase;

import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.chat2db.plugin.kingbase.constant.SqlConstant.*;
import static org.junit.jupiter.api.Assertions.*;

class KingBaseTableDDLTest {
    private static final String[] BASIC_COLUMNS = {"attname", "data_type", "column_default", "attnotnull"};
    private static final String[] IDENTITY_COLUMNS = {"attname", "data_type", "column_default", "attnotnull", "attidentity"};
    private static final String[] GENERATED_COLUMNS = {"attname", "data_type", "column_default", "attnotnull", "attgenerated"};
    private static final String[] SEQUENCE_COLUMNS = {"seqstart", "seqincrement", "seqmin", "seqmax", "seqcache", "seqcycle"};

    @Test
    void mysqlAutoIncrementPreservesSequenceNameAndCounterAfterKeys() throws Exception {
        for (boolean called : List.of(false, true)) {
            Fixture fixture = new Fixture(rows(IDENTITY_COLUMNS, new Object[]{"id", "bigint", null, true, "i"}));
            fixture.results.put(IDENTITY_SEQUENCE_SQL, rows(new String[]{"seqstart", "seqincrement", "seqmin", "seqmax",
                            "seqcache", "seqcycle", "sequence_schema", "sequence_name"},
                    new Object[]{1L, 1L, 1L, Long.MAX_VALUE, 1L, false, "sch\"ema", "seq'\"name"}));
            String stateQuery = SEQUENCE_STATE_SQL.formatted("\"sch\"\"ema\".\"seq'\"\"name\"");
            fixture.results.put(stateQuery, rows(new String[]{"last_value", "next_value", "is_called"},
                    new Object[]{3_000_000_000L, 4_000_000_000L, called}));
            String indexDDL = "CREATE INDEX id_idx ON \"app\".\"orders\" (id)";
            fixture.results.put(INDEX_SQL, rows(new String[]{"INDEXNAME", "INDEXDEF"}, new Object[]{"id_idx", indexDDL}));

            String ddl = fixture.export("app", "orders");

            String attach = "alter table \"app\".\"orders\" alter column \"id\" add auto_increment"
                    + " (sequence name \"sch\"\"ema\".\"seq'\"\"name\" start with 1 increment by 1 minvalue 1"
                    + " maxvalue 9223372036854775807 cache 1 no cycle), auto_increment = 4000000000;";
            assertTrue(ddl.contains(attach), ddl);
            assertTrue(ddl.indexOf(indexDDL) < ddl.indexOf(attach), ddl);
            assertTrue(ddl.contains("select pg_catalog.setval('\"sch\"\"ema\".\"seq''\"\"name\"', 3000000000, " + called + ");"), ddl);
            assertTrue(fixture.executed.contains(stateQuery));
        }
    }

    @Test
    void preservesAttachedEnumNameAndDefaultCast() throws Exception {
        String type = "ENUM('open', 'O''Brien') NAMES public.\"Enum_123\"";
        Fixture fixture = new Fixture(rows(BASIC_COLUMNS,
                new Object[]{"state", type, "'open'::public.\"Enum_123\"", false}));

        String ddl = fixture.export("app", "orders");

        assertTrue(ddl.contains("\"state\"  \t" + type + " default 'open'::public.\"Enum_123\""), ddl);
    }

    @Test
    void exportsTypesAndDefaultsWithoutInformationSchemaOrOptionalCatalogAttributes() throws Exception {
        Fixture fixture = new Fixture(rows(BASIC_COLUMNS,
                new Object[]{"select", "character varying(37)", "'O''Brien'::character varying", true},
                new Object[]{"amount", "numeric(18,4)", "0.1250", false},
                new Object[]{"tags", "integer[]", null, false},
                new Object[]{"state", "\"Types\".\"OrderState\"", null, true},
                new Object[]{"created_at", "timestamp(3) with time zone", "CURRENT_TIMESTAMP", false},
                new Object[]{"serial_id", "bigint", "nextval('app.id_seq'::regclass)", true}));

        String ddl = fixture.export("app", "orders");

        assertTrue(ddl.contains("\"select\"  \tcharacter varying(37) default 'O''Brien'::character varying not null,\n"), ddl);
        assertTrue(ddl.contains("\"amount\"  \tnumeric(18,4) default 0.1250,\n"), ddl);
        assertTrue(ddl.contains("\"tags\"  \tinteger[],\n"), ddl);
        assertTrue(ddl.contains("\"state\"  \t\"Types\".\"OrderState\" not null,\n"), ddl);
        assertTrue(ddl.contains("\"created_at\"  \ttimestamp(3) with time zone default CURRENT_TIMESTAMP,\n"), ddl);
        assertTrue(ddl.contains("\"serial_id\"  \tbigint default nextval('app.id_seq'::regclass) not null\n)"), ddl);
        assertFalse(fixture.executed.contains(IDENTITY_SEQUENCE_SQL));
        assertEquals(List.of("app", "orders"), fixture.parameters.get(COLUMN_SQL));
    }

    @Test
    void preservesIdentityModeAndLargeSequenceOptionsWithQuotedIdentifiers() throws Exception {
        for (String identity : List.of("a", "d")) {
            Fixture fixture = new Fixture(rows(IDENTITY_COLUMNS,
                    new Object[]{"id\"value", "bigint", null, true, identity}));
            fixture.results.put(IDENTITY_SEQUENCE_SQL, rows(SEQUENCE_COLUMNS,
                    new Object[]{3_000_000_000L, -2L, -9_000_000_000L, 9_000_000_000L, 32L, true}));

            String ddl = fixture.export("sch\"ema", "tab'le");

            assertTrue(ddl.startsWith("create table \"sch\"\"ema\".\"tab'le\""), ddl);
            assertTrue(ddl.contains("\"id\"\"value\"  \tbigint generated "
                    + (identity.equals("a") ? "always" : "by default")
                    + " as identity (start with 3000000000 increment by -2 minvalue -9000000000"
                    + " maxvalue 9000000000 cache 32 cycle) not null"), ddl);
            assertEquals(List.of("\"sch\"\"ema\".\"tab'le\"", "id\"value"), fixture.parameters.get(IDENTITY_SEQUENCE_SQL));
        }
    }

    @Test
    void detectsUppercaseMetadataLabelsAndPreservesNonCyclingIdentity() throws Exception {
        Fixture fixture = new Fixture(rows(new String[]{"ATTNAME", "DATA_TYPE", "COLUMN_DEFAULT", "ATTNOTNULL", "ATTIDENTITY"},
                new Object[]{"id", "integer", null, true, "d"}));
        fixture.results.put(IDENTITY_SEQUENCE_SQL, rows(SEQUENCE_COLUMNS,
                new Object[]{1L, 1L, 1L, 2_147_483_647L, 1L, false}));

        assertTrue(fixture.export("app", "orders").contains("cache 1 no cycle) not null"));
    }

    @Test
    void preservesGeneratedExpressionsInsteadOfExportingThemAsDefaults() throws Exception {
        for (String generated : List.of("s", "v")) {
            Fixture fixture = new Fixture(rows(GENERATED_COLUMNS,
                    new Object[]{"total", "numeric(18,4)", "(price * quantity)", true, generated}));

            String ddl = fixture.export("app", "orders");

            assertTrue(ddl.contains("generated always as ((price * quantity)) "
                    + (generated.equals("s") ? "stored" : "virtual") + " not null"), ddl);
            assertFalse(ddl.contains(" default "), ddl);
            assertFalse(fixture.executed.contains(IDENTITY_SEQUENCE_SQL));
        }
    }

    @Test
    void preservesConstraintsAfterTheLastColumn() throws Exception {
        Fixture fixture = new Fixture(rows(BASIC_COLUMNS, new Object[]{"id", "integer", null, true}));
        fixture.results.put(CONSTRAINT_SQL_VERSION_UNDER_ELEVEN,
                rows(new String[]{"CONSTRAINT_NAME", "CONSTRAINT_DEFINITION"}, new Object[]{"orders_pk", "PRIMARY KEY (id)"}));

        String ddl = fixture.export("app", "orders");

        assertTrue(ddl.contains("\"id\"  \tinteger not null,\n\t constraint \"orders_pk\" PRIMARY KEY (id)"), ddl);
    }

    @Test
    void failsWhenIdentitySequenceCannotBeReadInsteadOfChangingItsDefinition() throws Exception {
        Fixture fixture = new Fixture(rows(IDENTITY_COLUMNS, new Object[]{"id", "integer", null, true, "a"}));

        RuntimeException error = assertThrows(RuntimeException.class, () -> fixture.export("app", "orders"));

        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertEquals("Identity sequence metadata is missing", cause.getMessage());
    }

    private static CachedRowSet rows(String[] columns, Object[]... values) throws SQLException {
        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(columns.length);
        for (int i = 0; i < columns.length; i++) {
            metadata.setColumnName(i + 1, columns[i]);
            metadata.setColumnLabel(i + 1, columns[i]);
            Object sample = values.length == 0 ? null : values[0][i];
            metadata.setColumnType(i + 1, sample instanceof Boolean ? Types.BOOLEAN : sample instanceof Long ? Types.BIGINT : Types.VARCHAR);
        }
        rows.setMetaData(metadata);
        for (int i = values.length - 1; i >= 0; i--) {
            rows.moveToInsertRow();
            for (int j = 0; j < columns.length; j++) {
                if (values[i][j] == null) {
                    rows.updateNull(j + 1);
                } else {
                    rows.updateObject(j + 1, values[i][j]);
                }
            }
            rows.insertRow();
            rows.moveToCurrentRow();
        }
        rows.beforeFirst();
        return rows;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Fixture {
        private static final Set<String> QUERIES = Set.of(COLUMN_SQL, IDENTITY_SEQUENCE_SQL, TABLE_OPTION_SQL,
                CONSTRAINT_SQL_VERSION_UNDER_ELEVEN, TABLE_OWNER_SQL, TABLE_PRIVILEGE_SQL, has_parent_table_sql,
                INDEX_SQL, TABLE_INDEX_COMMENT_SQL);
        private final Map<String, ResultSet> results = new HashMap<>();
        private final Map<String, List<String>> parameters = new HashMap<>();
        private final List<String> executed = new ArrayList<>();

        private Fixture(ResultSet columns) {
            results.put(COLUMN_SQL, columns);
        }

        private String export(String schema, String table) {
            DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (p, method, args) -> {
                if (method.getName().equals("getDatabaseProductVersion")) {
                    return "9.0";
                }
                throw new UnsupportedOperationException(method.getName());
            });
            Connection connection = proxy(Connection.class, (p, method, args) -> switch (method.getName()) {
                case "getMetaData" -> metadata;
                case "prepareStatement" -> statement((String) args[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
            KingBaseMetaData kingbase = new KingBaseMetaData() {
                @Override
                public List<Table> tables(Connection connection, String databaseName, String schemaName, String tableName) {
                    return List.of();
                }

                @Override
                public List<TableColumn> columns(Connection connection, String databaseName, String schemaName, String tableName) {
                    return List.of();
                }
            };
            return kingbase.tableDDL(connection, "test", schema, table);
        }

        private PreparedStatement statement(String sql) throws SQLException {
            assertTrue(QUERIES.contains(sql) || results.containsKey(sql), "Unexpected query: " + sql);
            assertFalse(sql.contains("information_schema.columns"), "The compatibility view must not be required");
            ResultSet result = results.containsKey(sql) ? results.get(sql) : rows(new String[]{"unused"});
            List<String> bindings = new ArrayList<>();
            parameters.put(sql, bindings);
            return proxy(PreparedStatement.class, (p, method, args) -> switch (method.getName()) {
                case "setString" -> {
                    assertEquals(bindings.size() + 1, args[0]);
                    bindings.add((String) args[1]);
                    yield null;
                }
                case "execute" -> {
                    executed.add(sql);
                    yield true;
                }
                case "getResultSet" -> result;
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }
}
