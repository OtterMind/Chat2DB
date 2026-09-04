package ai.chat2db.community.domain.core.impl.db.extension;

import ai.chat2db.community.domain.api.enums.plugin.DataTypeEnum;
import ai.chat2db.community.domain.api.model.result.ExecuteResponse;
import ai.chat2db.community.domain.api.model.result.Header;
import ai.chat2db.community.domain.api.model.result.ResultCell;
import ai.chat2db.community.domain.api.model.sql.SqlExecuteRequest;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionContext;
import ai.chat2db.community.domain.api.model.sql.extension.SqlExecutionPlan;
import ai.chat2db.community.domain.api.model.sql.extension.SqlResultColumnContext;
import ai.chat2db.community.domain.api.service.db.ISqlExecutionResultConsumer;
import ai.chat2db.community.domain.api.service.db.extension.ISqlExecutionPolicy;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import ai.chat2db.spi.sql.Chat2DBContext;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class SqlExecutionPolicyManager {

    private final List<ISqlExecutionPolicy> policies;

    public SqlExecutionPolicyManager(List<ISqlExecutionPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public SqlExecutionPlan plan(SqlExecutionContext context) {
        return plan(context, null);
    }

    public SqlExecutionPlan plan(SqlExecutionContext context, String executionId) {
        String sql = context.getOriginalSql();
        Integer maxRows = null;
        for (ISqlExecutionPolicy policy : policies) {
            sql = Objects.requireNonNull(policy.rewriteSql(context, sql),
                    () -> policy.getClass().getName() + " returned a null SQL rewrite");
            if (StringUtils.isBlank(sql)) {
                throw new IllegalStateException(policy.getClass().getName() + " returned a blank SQL rewrite");
            }
            Integer policyMaxRows = policy.maxRows(context, sql);
            if (policyMaxRows != null && policyMaxRows < 1) {
                throw new IllegalStateException(policy.getClass().getName()
                        + " returned maxRows smaller than one");
            }
            if (policyMaxRows != null) {
                maxRows = maxRows == null ? policyMaxRows : Math.min(maxRows, policyMaxRows);
            }
        }
        return new SqlExecutionPlan(context, sql, maxRows, executionId);
    }

    public void applyMaxRows(SqlExecuteRequest request, SqlExecutionPlan plan) {
        Integer maxRows = plan.getMaxRows();
        if (maxRows == null) {
            return;
        }
        Integer requestedPageSize = request.getPageSize();
        if (requestedPageSize == null || requestedPageSize < 1 || requestedPageSize > maxRows) {
            request.setPageSize(maxRows);
        }
        request.setPageSizeAll(false);
    }

    public void beforeExecute(SqlExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Chat2DBContext.beforeExecute(plan);
        policies.forEach(policy -> policy.beforeExecute(plan));
    }

    public void checkpoint(SqlExecutionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        policies.forEach(policy -> policy.checkpoint(plan));
    }

    public List<ExecuteResponse> filterResultColumns(SqlExecutionPlan plan, List<ExecuteResponse> results) {
        if (CollectionUtils.isEmpty(results)) {
            return results;
        }
        results.forEach(result -> {
            limitResultRows(plan, result);
            filterResultColumns(plan, result);
        });
        return results;
    }

    public ISqlExecutionResultConsumer wrapStreamingConsumer(SqlExecutionPlan plan,
            ISqlExecutionResultConsumer delegate) {
        return new PolicyAwareStreamingConsumer(plan, Objects.requireNonNull(delegate, "delegate"));
    }

    public long limitCount(SqlExecutionPlan plan, long count) {
        Integer maxRows = plan.getMaxRows();
        return maxRows == null ? count : Math.min(count, maxRows.longValue());
    }

    public boolean isRowAllowed(SqlExecutionPlan plan, int exportedRowCount) {
        Integer maxRows = plan.getMaxRows();
        return maxRows == null || exportedRowCount < maxRows;
    }

    public boolean isEmpty() {
        return policies.isEmpty();
    }

    public boolean includeColumn(SqlResultColumnContext context) {
        for (ISqlExecutionPolicy policy : policies) {
            if (!policy.includeColumn(context)) {
                return false;
            }
        }
        return true;
    }

    private void limitResultRows(SqlExecutionPlan plan, ExecuteResponse result) {
        Integer maxRows = plan.getMaxRows();
        List<List<ResultCell>> rows = result.getDataList();
        if (maxRows == null || rows == null || rows.size() < maxRows) {
            return;
        }
        if (rows.size() > maxRows) {
            result.setDataList(new ArrayList<>(rows.subList(0, maxRows)));
        }
        result.setHasNextPage(false);
        result.setFuzzyTotal(Integer.toString(maxRows));
    }

    private void filterResultColumns(SqlExecutionPlan plan, ExecuteResponse result) {
        List<Header> headers = result.getHeaderList();
        if (CollectionUtils.isEmpty(headers)) {
            result.setCanEdit(false);
            return;
        }
        List<Integer> includedIndexes = includedColumnIndexes(plan, headers);
        boolean columnsFiltered = includedIndexes.size() != headers.size();
        if (columnsFiltered) {
            result.setHeaderList(select(headers, includedIndexes));
            if (result.getDataList() != null) {
                result.setDataList(selectRows(result.getDataList(), includedIndexes));
            }
        }
        result.setCanEdit(result.isCanEdit() && !columnsFiltered
                && policiesAllowEditing(plan, headers));
    }

    public List<Integer> includedColumnIndexes(SqlExecutionPlan plan, List<Header> headers) {
        List<Integer> includedIndexes = new ArrayList<>(headers.size());
        for (int index = 0; index < headers.size(); index++) {
            Header header = headers.get(index);
            boolean synthetic = header != null
                    && Objects.equals(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode(), header.getDataType());
            if (synthetic || includeColumn(toColumnContext(plan, index + 1, header, synthetic))) {
                includedIndexes.add(index);
            }
        }
        return includedIndexes;
    }

    private SqlResultColumnContext toColumnContext(SqlExecutionPlan plan, int columnIndex, Header header,
            boolean synthetic) {
        return new SqlResultColumnContext(plan, columnIndex,
                header == null ? null : header.getColumnName(),
                header == null ? null : header.getName(), null,
                header == null ? null : header.getColumnType(),
                header == null ? null : header.getDatabaseName(),
                header == null ? null : header.getSchemaName(),
                header == null ? null : header.getTableName(), synthetic);
    }

    private boolean policiesAllowEditing(SqlExecutionPlan plan, List<Header> headers) {
        List<SqlResultColumnContext> columns = new ArrayList<>(headers.size());
        for (int index = 0; index < headers.size(); index++) {
            Header header = headers.get(index);
            boolean synthetic = header != null
                    && Objects.equals(DataTypeEnum.CHAT2DB_ROW_NUMBER.getCode(), header.getDataType());
            columns.add(toColumnContext(plan, index + 1, header, synthetic));
        }
        for (ISqlExecutionPolicy policy : policies) {
            if (!policy.canEditResult(plan, List.copyOf(columns))) {
                return false;
            }
        }
        return true;
    }

    private <T> List<T> select(List<T> values, List<Integer> includedIndexes) {
        List<T> selected = new ArrayList<>(includedIndexes.size());
        for (Integer index : includedIndexes) {
            if (index < values.size()) {
                selected.add(values.get(index));
            }
        }
        return selected;
    }

    private List<List<ResultCell>> selectRows(List<List<ResultCell>> rows, List<Integer> includedIndexes) {
        List<List<ResultCell>> filteredRows = new ArrayList<>(rows.size());
        for (List<ResultCell> row : rows) {
            filteredRows.add(row == null ? null : select(row, includedIndexes));
        }
        return filteredRows;
    }

    private List<List<ResultCell>> limitStreamingRows(SqlExecutionPlan plan, List<List<ResultCell>> rows,
            int emittedRows) {
        if (CollectionUtils.isEmpty(rows) || plan.getMaxRows() == null) {
            return rows;
        }
        int remaining = Math.max(plan.getMaxRows() - emittedRows, 0);
        if (remaining == 0) {
            return List.of();
        }
        if (rows.size() <= remaining) {
            return rows;
        }
        return new ArrayList<>(rows.subList(0, remaining));
    }

    private final class PolicyAwareStreamingConsumer implements ISqlExecutionResultConsumer {

        private final SqlExecutionPlan plan;
        private final ISqlExecutionResultConsumer delegate;
        private final Map<ExecuteResponse, StreamingResultState> states = new IdentityHashMap<>();

        private PolicyAwareStreamingConsumer(SqlExecutionPlan plan, ISqlExecutionResultConsumer delegate) {
            this.plan = Objects.requireNonNull(plan, "plan");
            this.delegate = delegate;
        }

        @Override
        public void statementStarted(String sql, String originalSql, String comment) {
            delegate.statementStarted(sql, originalSql, comment);
        }

        @Override
        public void resultStarted(ExecuteResponse result) {
            StreamingResultState state = stateFor(result);
            if (state.columnsFiltered) {
                result.setHeaderList(select(result.getHeaderList(), state.includedIndexes));
            }
            result.setCanEdit(state.canEdit);
            delegate.resultStarted(result);
        }

        @Override
        public void rows(ExecuteResponse result, List<List<ResultCell>> rows) {
            StreamingResultState state = stateFor(result);
            List<List<ResultCell>> limitedRows = limitStreamingRows(plan, rows, state.emittedRows);
            if (CollectionUtils.isEmpty(limitedRows)) {
                return;
            }
            state.emittedRows += limitedRows.size();
            delegate.rows(result, state.columnsFiltered
                    ? selectRows(limitedRows, state.includedIndexes)
                    : limitedRows);
        }

        @Override
        public void resultFinished(ExecuteResponse result) {
            StreamingResultState state = stateFor(result);
            limitResultRows(plan, result);
            if (state.columnsFiltered && result.getDataList() != null) {
                result.setDataList(selectRows(result.getDataList(), state.includedIndexes));
            }
            result.setCanEdit(state.canEdit);
            try {
                delegate.resultFinished(result);
            } finally {
                states.remove(result);
            }
        }

        @Override
        public void updateCount(ExecuteResponse result) {
            delegate.updateCount(result);
        }

        @Override
        public void statementFinished(String sql, long duration) {
            delegate.statementFinished(sql, duration);
        }

        private StreamingResultState stateFor(ExecuteResponse result) {
            return states.computeIfAbsent(result, key -> {
                List<Header> headers = key.getHeaderList();
                if (CollectionUtils.isEmpty(headers)) {
                    return new StreamingResultState(List.of(), false, false);
                }
                List<Integer> includedIndexes = includedColumnIndexes(plan, headers);
                boolean columnsFiltered = includedIndexes.size() != headers.size();
                boolean canEdit = key.isCanEdit() && !columnsFiltered && policiesAllowEditing(plan, headers);
                return new StreamingResultState(includedIndexes, columnsFiltered, canEdit);
            });
        }
    }

    private static final class StreamingResultState {

        private final List<Integer> includedIndexes;
        private final boolean columnsFiltered;
        private final boolean canEdit;
        private int emittedRows;

        private StreamingResultState(List<Integer> includedIndexes, boolean columnsFiltered, boolean canEdit) {
            this.includedIndexes = includedIndexes;
            this.columnsFiltered = columnsFiltered;
            this.canEdit = canEdit;
        }
    }
}
