package ai.chat2db.plugin.mysql.completion.hint;

import ai.chat2db.plugin.mysql.model.completion.context.MysqlSqlCompletionCandidateContext;
import ai.chat2db.plugin.mysql.model.completion.context.MysqlSqlCompletionInsertStatementContext;
import ai.chat2db.plugin.mysql.completion.util.MysqlSqlCompletionTokenUtil;
import ai.chat2db.plugin.mysql.completion.value.MysqlSqlCompletionValueDefaults;
import ai.chat2db.community.domain.api.enums.completion.SqlCompletionEditorHintTypeEnum;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionCandidate;
import ai.chat2db.community.domain.api.model.completion.SqlCompletionEditorHint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;


final class MysqlSqlCompletionInsertValueHintBuilder {

    List<SqlCompletionEditorHint> build(MysqlSqlCompletionCandidateContext context) {
        if (context == null || context.window() == null || context.metadataProvider() == null
                || context.insertStatementContext() == null) {
            return List.of();
        }
        String sql = Objects.toString(context.window().parseSql(), "");
        int cursor = Math.max(0, Math.min(context.window().cursor(), sql.length()));
        int sourceOffset = context.window().sourceStartOffset();
        String sourceSql = Objects.toString(context.input().sourceSql(), "");
        int sourceCursor = Math.max(0, Math.min(context.window().sourceCursor(), sourceSql.length()));
        if (MysqlSqlCompletionTokenUtil.cursorInsideRawLiteralOrComment(sourceSql, sourceCursor)
                || MysqlSqlCompletionTokenUtil.cursorInsideRawLiteralOrComment(sql, cursor)
                || MysqlSqlCompletionTokenUtil.isTriggerPseudoRecordQualifierAtCursor(sourceSql, sourceCursor)
                || MysqlSqlCompletionTokenUtil.isTriggerPseudoRecordQualifierAtCursor(sql, cursor)) {
            return List.of();
        }

        MysqlSqlCompletionInsertStatementContext insertContext = context.insertStatementContext();
        if (!insertContext.active() || !insertContext.hasExplicitColumnList() || !insertContext.hasValueRows()) {
            return List.of();
        }

        Map<String, SqlCompletionCandidate> metadataColumns = MysqlSqlCompletionColumnMetadata.load(
                context.metadataProvider(), insertContext.tableRef().catalog(), insertContext.tableRef().schema(),
                insertContext.tableRef().table());
        List<SqlCompletionEditorHint> hints = new ArrayList<>();
        for (MysqlSqlCompletionInsertStatementContext.RowWindow rowWindow : insertContext.valueRows()) {
            SqlCompletionEditorHint hint = buildRowHint(sourceSql, sourceOffset, sql.length(), rowWindow,
                    insertContext.columnWindow(), metadataColumns, cursor);
            if (hint != null) {
                hints.add(hint);
            }
        }
        return hints;
    }

    private SqlCompletionEditorHint buildRowHint(String sourceSql,
                                                 int sourceOffset,
                                                 int sqlLength,
                                                 MysqlSqlCompletionInsertStatementContext.RowWindow rowWindow,
                                                 MysqlSqlCompletionInsertStatementContext.ColumnWindow columnWindow,
                                                 Map<String, SqlCompletionCandidate> metadataColumns,
                                                 int cursor) {
        if (rowWindow.active() && rowWindow.activeColumnIndex() < 0) {
            return null;
        }
        MysqlSqlCompletionInsertStatementContext.ValueRange activeValueRange = rowWindow.active()
                ? valueRangeAt(rowWindow, rowWindow.activeColumnIndex(), cursor)
                : null;
        SqlCompletionEditorHint hint = new SqlCompletionEditorHint();
        hint.setType(SqlCompletionEditorHintTypeEnum.INSERT_VALUE);
        hint.setStatementRange(range(sourceSql, sourceOffset, 0, sqlLength));
        hint.setRowRange(range(sourceSql, sourceOffset, rowWindow.rowStartOffset(), rowWindow.rowEndOffset()));
        if (activeValueRange != null) {
            hint.setValueRange(range(sourceSql, sourceOffset, activeValueRange.startOffset(),
                    activeValueRange.endOffset()));
        }

        List<SqlCompletionEditorHint.Item> items = new ArrayList<>();
        for (int i = 0; i < columnWindow.columns().size(); i++) {
            MysqlSqlCompletionInsertStatementContext.ColumnRange column = columnWindow.columns().get(i);
            MysqlSqlCompletionInsertStatementContext.ValueRange valueRange = valueRangeAt(rowWindow, i, cursor);
            String columnType = MysqlSqlCompletionColumnMetadata.columnType(
                    MysqlSqlCompletionColumnMetadata.find(metadataColumns, column.name()));
            SqlCompletionEditorHint.Item item = new SqlCompletionEditorHint.Item();
            item.setRowIndex(rowWindow.rowIndex());
            item.setColumnIndex(i);
            item.setFieldName(column.name());
            item.setFieldType(columnType);
            item.setDefaultValue(MysqlSqlCompletionValueDefaults.defaultValue(columnType));
            item.setLabel(StringUtils.isBlank(columnType) ? column.name() : column.name() + ":" + columnType);
            item.setRange(range(sourceSql, sourceOffset, valueRange.startOffset(), valueRange.endOffset()));
            item.setActive(rowWindow.active() && i == rowWindow.activeColumnIndex());
            items.add(item);
        }
        hint.setItems(items);
        return items.isEmpty() ? null : hint;
    }

    private MysqlSqlCompletionInsertStatementContext.ValueRange valueRangeAt(
            MysqlSqlCompletionInsertStatementContext.RowWindow rowWindow,
            int index,
            int cursor) {
        if (index >= 0 && index < rowWindow.valueRanges().size()) {
            return rowWindow.valueRanges().get(index);
        }
        int offset = rowWindow.active()
                ? Math.max(rowWindow.rowStartOffset(), Math.min(cursor, rowWindow.rowEndOffset()))
                : Math.max(rowWindow.rowStartOffset(), rowWindow.rowEndOffset());
        return new MysqlSqlCompletionInsertStatementContext.ValueRange(offset, offset);
    }

    private SqlCompletionEditorHint.Range range(String sourceSql, int sourceOffset, int startOffset, int endOffset) {
        return SqlCompletionEditorHint.Range.ofOffsets(sourceSql, sourceOffset + startOffset, sourceOffset + endOffset);
    }

}
