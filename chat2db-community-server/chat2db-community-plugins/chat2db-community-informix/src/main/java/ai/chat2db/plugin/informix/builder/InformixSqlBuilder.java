package ai.chat2db.plugin.informix.builder;

import ai.chat2db.community.domain.api.enums.plugin.EditStatusEnum;
import ai.chat2db.community.domain.api.enums.plugin.IndexTypeEnum;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import ai.chat2db.community.domain.api.model.metadata.TableIndexColumn;
import ai.chat2db.spi.DefaultSqlBuilder;
import org.apache.commons.lang3.StringUtils;

/**
 * Informix SQL builder. Overrides only the operations confirmed to use
 * MySQL/PG-style syntax Informix rejects: table rename, column modify, and
 * EXPLAIN. Add/drop column, COMMENT, and index syntax are intentionally left
 * unchanged from the default (only rename/modify/explain were confirmed wrong).
 */
public class InformixSqlBuilder extends DefaultSqlBuilder {

    @Override
    public String buildExplain(String sql) {
        // Informix enables the query plan via SET EXPLAIN ON (not EXPLAIN <sql>).
        return "SET EXPLAIN ON; " + sql;
    }

    @Override
    public String buildAlterTable(Table oldTable, Table newTable) {
        // Mirrors DBStructUtils.buildAlterTable except for the Informix-confirmed
        // table-rename and column-modify syntax. Add/drop column, comments, and
        // indexes are intentionally unchanged.
        StringBuilder script = new StringBuilder();
        if (!StringUtils.equalsIgnoreCase(oldTable.getName(), newTable.getName())) {
            // Informix: RENAME TABLE old TO new (not ALTER TABLE old RENAME TO new)
            script.append("RENAME TABLE ").append(oldTable.getName()).append(" TO ")
                    .append(newTable.getName()).append(";\n");
        }
        if (!StringUtils.equalsIgnoreCase(oldTable.getComment(), newTable.getComment())) {
            script.append(generateTableCommentSQL(newTable.getName(), newTable.getComment())).append("\n");
        }
        for (TableColumn tableColumn : newTable.getColumnList()) {
            if (StringUtils.isNotBlank(tableColumn.getEditStatus())
                    && StringUtils.isNotBlank(tableColumn.getColumnType())
                    && StringUtils.isNotBlank(tableColumn.getName())) {
                script.append(generateColumnAlterSQL(tableColumn)).append("\n");
            }
        }
        for (TableIndex tableIndex : newTable.getIndexList()) {
            if (StringUtils.isNotBlank(tableIndex.getEditStatus())
                    && StringUtils.isNotBlank(tableIndex.getType())) {
                script.append(generateIndexAlterSQL(tableIndex)).append("\n");
            }
        }
        return script.toString();
    }

    private String generateColumnAlterSQL(TableColumn tableColumn) {
        if (EditStatusEnum.DELETE.name().equals(tableColumn.getEditStatus())) {
            return "ALTER TABLE " + tableColumn.getTableName() + " DROP COLUMN " + tableColumn.getName() + ";";
        }
        if (EditStatusEnum.ADD.name().equals(tableColumn.getEditStatus())) {
            return "ALTER TABLE " + tableColumn.getTableName() + " ADD COLUMN " + tableColumn.getName() + " "
                    + tableColumn.getColumnType() + ";";
        }
        if (EditStatusEnum.MODIFY.name().equals(tableColumn.getEditStatus())) {
            // Informix: ALTER TABLE t MODIFY (col type) (not MODIFY COLUMN col type)
            return "ALTER TABLE " + tableColumn.getTableName() + " MODIFY (" + tableColumn.getName() + " "
                    + tableColumn.getColumnType() + ");";
        }
        if (tableColumn.getComment() != null) {
            return "COMMENT ON COLUMN " + tableColumn.getTableName() + "." + tableColumn.getName()
                    + " IS '" + tableColumn.getComment().replace("'", "''") + "';";
        }
        return "";
    }

    private String generateIndexAlterSQL(TableIndex tableIndex) {
        if (EditStatusEnum.DELETE.name().equals(tableIndex.getEditStatus())) {
            return "DROP INDEX " + tableIndex.getName() + ";";
        }
        if (EditStatusEnum.ADD.name().equals(tableIndex.getEditStatus())) {
            StringBuilder columnNames = new StringBuilder();
            for (TableIndexColumn column : tableIndex.getColumnList()) {
                if (columnNames.length() > 0) {
                    columnNames.append(", ");
                }
                columnNames.append(column.getColumnName());
            }
            boolean unique = IndexTypeEnum.UNIQUE.getName().equals(tableIndex.getType());
            return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + tableIndex.getName() + " ON "
                    + tableIndex.getTableName() + " (" + columnNames + ");";
        }
        return "";
    }

    private String generateTableCommentSQL(String tableName, String comment) {
        if (comment == null) {
            return "COMMENT ON TABLE " + tableName + " IS NULL;";
        }
        return "COMMENT ON TABLE " + tableName + " IS '" + comment.replace("'", "''") + "';";
    }
}
