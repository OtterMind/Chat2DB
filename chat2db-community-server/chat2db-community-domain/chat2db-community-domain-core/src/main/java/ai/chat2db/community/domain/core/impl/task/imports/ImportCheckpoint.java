package ai.chat2db.community.domain.core.impl.task.imports;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * Resumable position of one import run.
 *
 * <p>The source position is a row number, not a byte offset: a resumed task skips the first
 * {@code processedRows} data rows of the same file. That only reproduces the original run while the
 * source file is unchanged, which is why every resume writes that precondition into the task events.
 *
 * @param processedRows source data rows already stored by the finished run
 * @param targetTable   target table the rows were written to
 * @param conflictPolicy duplicate policy used by the finished run
 */
public record ImportCheckpoint(long processedRows, String targetTable, String conflictPolicy) {

    public static final String PROCESSED_ROWS_KEY = "processedRows";

    public static final String TARGET_TABLE_KEY = "targetTable";

    public static final String CONFLICT_POLICY_KEY = "conflictPolicy";

    public String toJson() {
        JSONObject json = new JSONObject();
        json.put(PROCESSED_ROWS_KEY, processedRows);
        json.put(TARGET_TABLE_KEY, targetTable);
        json.put(CONFLICT_POLICY_KEY, conflictPolicy);
        return json.toJSONString();
    }

    /**
     * Parses a stored checkpoint.
     *
     * @throws IllegalArgumentException when the payload is not a checkpoint object or carries no
     *         usable row position
     */
    public static ImportCheckpoint fromJson(String checkpointJson) {
        if (checkpointJson == null || checkpointJson.isBlank()) {
            throw new IllegalArgumentException("Import checkpoint is empty");
        }
        JSONObject json;
        try {
            json = JSON.parseObject(checkpointJson);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Import checkpoint is not a JSON object", e);
        }
        if (json == null || json.get(PROCESSED_ROWS_KEY) == null) {
            throw new IllegalArgumentException("Import checkpoint has no " + PROCESSED_ROWS_KEY);
        }
        long processedRows;
        try {
            processedRows = Math.max(0L, json.getLongValue(PROCESSED_ROWS_KEY));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Import checkpoint has an invalid " + PROCESSED_ROWS_KEY, e);
        }
        return new ImportCheckpoint(processedRows, json.getString(TARGET_TABLE_KEY),
                json.getString(CONFLICT_POLICY_KEY));
    }
}
