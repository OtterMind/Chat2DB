package ai.chat2db.plugin.mysql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlObj007FixtureTest {

    @Test
    void shouldKeepForeignKeyFixtureSyntaxAndSetNullSemanticsValid() throws IOException {
        String sql = Files.readString(fixturePath());

        assertTrue(sql.contains("CONSTRAINT `fk_emp_dept` FOREIGN KEY (`dept_id`)"), sql);
        assertTrue(sql.contains("CONSTRAINT `fk_pm_project` FOREIGN KEY (`project_id`, `department_id`)"), sql);
        assertTrue(sql.contains("CONSTRAINT `fk_pm_emp` FOREIGN KEY (`emp_id`)"), sql);
        assertTrue(sql.contains("CONSTRAINT `fk_cat_parent` FOREIGN KEY (`parent_id`)"), sql);

        String projectMembers = tableDefinition(sql, "obj007_project_members");
        assertTrue(projectMembers.contains("`emp_id` BIGINT NULL"), projectMembers);
        assertTrue(projectMembers.contains("PRIMARY KEY (`member_id`)"), projectMembers);
        assertTrue(projectMembers.contains("UNIQUE KEY `uk_pm_project_emp` (`project_id`, `department_id`, `emp_id`)"),
                projectMembers);
        assertFalse(projectMembers.contains("PRIMARY KEY (`project_id`, `department_id`, `emp_id`)"), projectMembers);

        assertSetNullColumnIsNullable(sql, "obj007_project_members", "emp_id");
        assertSetNullColumnIsNullable(sql, "obj007_categories", "parent_id");
    }

    private static void assertSetNullColumnIsNullable(String sql, String tableName, String columnName) {
        String table = tableDefinition(sql, tableName);
        assertTrue(table.contains("ON DELETE SET NULL"), table);
        String columnDefinition = table.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("`" + columnName + "`"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing column " + tableName + "." + columnName));
        assertFalse(columnDefinition.toUpperCase().contains("NOT NULL"), columnDefinition);
    }

    private static String tableDefinition(String sql, String tableName) {
        String startToken = "CREATE TABLE IF NOT EXISTS `" + tableName + "` (";
        int start = sql.indexOf(startToken);
        if (start < 0) {
            throw new AssertionError("Missing table " + tableName);
        }
        int end = sql.indexOf(") ENGINE=InnoDB", start);
        if (end < 0) {
            throw new AssertionError("Missing InnoDB table terminator for " + tableName);
        }
        return sql.substring(start, end);
    }

    private static Path fixturePath() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("script/test-fixtures/mysql/MYSQL-OBJ-007/init.sql");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate MYSQL-OBJ-007 fixture from " + System.getProperty("user.dir"));
    }
}
