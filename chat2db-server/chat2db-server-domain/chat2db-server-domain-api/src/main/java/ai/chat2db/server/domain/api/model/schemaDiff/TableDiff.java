package ai.chat2db.server.domain.api.model.schemaDiff;

import java.util.List;

import ai.chat2db.spi.model.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TableDiff {
    private String tableName;
    private TableDiffType diffType;
    private Table sourceTable;
    private Table targetTable;
    private List<ColumnDiff> columnDiffs;
    private List<IndexDiff> indexDiffs;
    private List<ForeignKeyDiff> foreignKeyDiffs;
    private List<FieldDiff> tableOptionDiffs;
    private List<String> ddlStatements;
    private String ddlStatement;
}
