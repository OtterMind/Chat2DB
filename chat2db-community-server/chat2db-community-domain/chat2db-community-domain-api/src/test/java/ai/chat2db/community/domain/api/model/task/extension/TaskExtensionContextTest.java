package ai.chat2db.community.domain.api.model.task.extension;

import ai.chat2db.community.domain.api.model.task.TaskType;
import ai.chat2db.community.domain.api.model.runtime.ConnectionProfile;
import org.junit.jupiter.api.Test;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExtensionContextTest {

    @Test
    void taskContextsDefensivelyCopyConnectionAndTableSnapshots() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setDataSourceId(7L);
        profile.setDbType("MYSQL");
        List<String> tables = new ArrayList<>(List.of("orders"));

        TaskSubmissionContext submission = new TaskSubmissionContext(42L,
                TaskType.TABLE_DATA_EXPORT, profile, "shop", null, tables, TaskOperation.EXPORT);
        profile.setDataSourceId(9L);
        tables.add("users");

        assertEquals(7L, submission.getConnectionProfile().getDataSourceId());
        assertEquals(List.of("orders"), submission.getTableNames());
        assertThrows(UnsupportedOperationException.class, () -> submission.getTableNames().add("audit"));

        TaskExecutionContext execution = submission.toExecutionContext();
        ConnectionProfile returnedProfile = execution.getConnectionProfile();
        returnedProfile.setDataSourceId(11L);

        assertNotSame(returnedProfile, execution.getConnectionProfile());
        assertEquals(7L, execution.getConnectionProfile().getDataSourceId());
        assertEquals(List.of("orders"), execution.getTableNames());
    }

    @Test
    void statementContextUsesStableSha256Digest() {
        TaskExecutionContext execution = new TaskExecutionContext(42L, TaskType.DATA_FILE_IMPORT,
                null, "shop", null, List.of("orders"), TaskOperation.IMPORT);

        TaskStatementContext statement = new TaskStatementContext(execution, "select 1");

        assertEquals("822ae07d4783158bc1912bb623e5107cc9002d519e1143a9c200ed6ee18b6d0f",
                statement.getSqlDigest());
        assertEquals("select 1", statement.getSql());
        assertEquals(execution, statement.getTaskContext());
    }

    @Test
    void exportCellDoesNotExposeMutableArrayValues() {
        byte[] value = new byte[]{1, 2, 3};
        ExportCell cell = new ExportCell(value, -2, "BINARY", 3, 0);
        value[0] = 9;

        byte[] returned = (byte[]) cell.getValue();
        returned[1] = 8;

        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) cell.getValue());
        ExportCell masked = cell.withValue("***");
        assertEquals("***", masked.getValue());
        assertEquals(-2, masked.getJdbcType());
        assertEquals("BINARY", masked.getTypeName());
        assertTrue(new ExportCell(null, 12, "VARCHAR", 20, 0).isNullValue());
    }

    @Test
    void exportCellDeepCopiesNestedCollectionsAndUnknownMutableValues() {
        byte[] nestedBytes = new byte[]{1, 2};
        List<Object> values = new ArrayList<>();
        values.add(nestedBytes);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("values", values);
        source.put("builder", new StringBuilder("stable"));

        ExportCell cell = new ExportCell(source, 1111, "OTHER", 0, 0);
        nestedBytes[0] = 9;
        values.clear();

        Map<?, ?> returned = (Map<?, ?>) cell.getValue();
        List<?> returnedValues = (List<?>) returned.get("values");
        byte[] returnedBytes = (byte[]) returnedValues.get(0);
        returnedBytes[1] = 8;

        Map<?, ?> secondRead = (Map<?, ?>) cell.getValue();
        assertArrayEquals(new byte[]{1, 2}, (byte[]) ((List<?>) secondRead.get("values")).get(0));
        assertEquals("stable", secondRead.get("builder"));
        assertThrows(UnsupportedOperationException.class, returned::clear);
    }

    @Test
    void exportCellSnapshotsJdbcLobValues() throws Exception {
        ExportCell blobCell = new ExportCell(new SerialBlob(new byte[]{1, 2, 3}), -4, "BLOB", 3, 0);
        ExportCell clobCell = new ExportCell(new SerialClob("content".toCharArray()), 2005, "CLOB", 7, 0);

        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) blobCell.getValue());
        assertEquals("content", clobCell.getValue());
    }
}
