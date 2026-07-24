package ai.chat2db.community.domain.api.model.completion;

import ai.chat2db.community.domain.api.enums.completion.SqlCompletionEditorHintTypeEnum;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Data;


@Data
public class SqlCompletionEditorHint {

    private SqlCompletionEditorHintTypeEnum type;
    private Range statementRange;
    private Range rowRange;
    private Range valueRange;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        private int rowIndex;
        private int columnIndex;
        private String fieldName;
        private String fieldType;
        private String defaultValue;
        private String label;
        private Range range;
        private boolean active;
    }

    @Data
    public static class Range {
        private int startLineNumber;
        private int startColumn;
        private int endLineNumber;
        private int endColumn;

        public static Range ofOffsets(String sql, int startOffset, int endOffset) {
            int safeStart = safeOffset(sql, startOffset);
            int safeEnd = Math.max(safeStart, safeOffset(sql, endOffset));
            int[] start = rowColAt(sql, safeStart);
            int[] end = rowColAt(sql, safeEnd);
            Range range = new Range();
            range.setStartLineNumber(start[0]);
            range.setStartColumn(start[1]);
            range.setEndLineNumber(end[0]);
            range.setEndColumn(end[1]);
            return range;
        }

        private static int safeOffset(String sql, int offset) {
            int length = Objects.isNull(sql) ? 0 : sql.length();
            return Math.max(0, Math.min(offset, length));
        }

        private static int[] rowColAt(String sql, int offset) {
            int row = 1;
            int col = 1;
            int safeOffset = safeOffset(sql, offset);
            String value = Objects.isNull(sql) ? "" : sql;
            for (int i = 0; i < safeOffset; i++) {
                if (value.charAt(i) == '\n') {
                    row++;
                    col = 1;
                } else {
                    col++;
                }
            }
            return new int[]{row, col};
        }
    }
}
