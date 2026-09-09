package ai.chat2db.plugin.db2;

import ai.chat2db.plugin.db2.constant.DB2DBManagerConstants;
import ai.chat2db.plugin.db2.constant.DB2MetaDataConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB2 JDBC rejects a trailing statement terminator (";") with SQLCODE -104
 * (SQLSTATE 42601, ERRMC ... END-OF-STATEMENT) because prepareStatement and
 * Statement.execute accept exactly one statement. These guards keep every
 * JDBC-executable DB2 constant terminator-free; see issue #2703 where opening
 * a stored procedure failed because ROUTINE_DDL_SQL ended with ";".
 */
class DB2SqlConstantsTest {

    @Test
    void routineAndViewDdlQueriesAreExecutableSingleStatements() {
        assertEquals(
                "select TEXT from syscat.routines where ROUTINESCHEMA='MALCA0' and ROUTINENAME='P_ADS' and ROUTINETYPE='P'",
                String.format(DB2MetaDataConstants.ROUTINE_DDL_SQL, "MALCA0", "P_ADS", 'P'));
        assertEquals(
                "select TEXT from syscat.routines where ROUTINESCHEMA='MALCA0' and ROUTINENAME='F_ADS' and ROUTINETYPE='F'",
                String.format(DB2MetaDataConstants.ROUTINE_DDL_SQL, "MALCA0", "F_ADS", 'F'));
        assertEquals(
                "select TEXT from syscat.views where VIEWSCHEMA='MALCA0' and VIEWNAME='V_ADS'",
                String.format(DB2MetaDataConstants.VIEW_DDL_SQL, "MALCA0", "V_ADS"));
    }

    @Test
    void jdbcExecutableConstantsCarryNoStatementTerminator() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> constantsClass : List.of(DB2MetaDataConstants.class, DB2DBManagerConstants.class)) {
            for (Field field : constantsClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    String value = (String) field.get(null);
                    if (value != null && value.trim().endsWith(";")) {
                        offenders.add(constantsClass.getSimpleName() + "." + field.getName());
                    }
                } catch (IllegalAccessException ignored) {
                    // unreachable for static fields with setAccessible
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "DB2 JDBC raises SQLCODE -104 for a trailing ';' — offending constants: " + offenders);
    }
}
