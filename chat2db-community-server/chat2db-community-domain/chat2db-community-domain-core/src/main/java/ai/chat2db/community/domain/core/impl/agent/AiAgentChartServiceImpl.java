package ai.chat2db.community.domain.core.impl.agent;

import ai.chat2db.community.domain.api.enums.agent.AiAgentChartType;
import ai.chat2db.community.domain.api.model.agent.chart.AiAgentChart;
import ai.chat2db.community.domain.api.model.agent.chart.DbAgentQueryResult;
import ai.chat2db.community.domain.api.model.agent.tool.AgentToolExecutionContext;
import ai.chat2db.community.domain.api.model.request.agent.AiAgentChartRenderRequest;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.SqlExecutionData;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse.SqlResult;
import ai.chat2db.community.domain.api.model.response.agent.DbAgentDatabaseResponse;
import ai.chat2db.community.domain.api.service.agent.IAgentQueryResultStorage;
import ai.chat2db.community.domain.api.service.agent.IAiAgentChartService;
import ai.chat2db.community.domain.core.converter.agent.AgentChartConverter;
import ai.chat2db.community.tools.enums.agent.AgentEventType;
import ai.chat2db.community.tools.exception.agent.AgentChartException;
import ai.chat2db.community.tools.model.agent.runtime.AgentRuntimeEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AiAgentChartServiceImpl implements IAiAgentChartService {
    private final IAgentQueryResultStorage results;
    private final ObjectMapper json = new ObjectMapper();

    public AiAgentChartServiceImpl(IAgentQueryResultStorage results) { this.results = results; }

    @Override
    public DbAgentDatabaseResponse<SqlExecutionData> captureQueryResults(
            DbAgentDatabaseResponse<SqlExecutionData> response, AgentToolExecutionContext context) {
        if (context == null || response.data() == null) return response;
        List<SqlResult> referenced = new ArrayList<>();
        List<String> warnings = new ArrayList<>(response.warnings());
        for (SqlResult result : response.data().results()) {
            String id = null;
            if (result.success() && result.data() != null && !result.data().columns().isEmpty()) {
                String generatedId = UUID.randomUUID().toString();
                try {
                    results.create(AgentChartConverter.query2snapshot(generatedId, result, response, context), context.userId());
                    id = generatedId;
                    var output = results.output(context.sessionId(), id, context.userId());
                    if (output != null && !output.complete()) warnings.add(Objects.toString(output.warning(), "Only partial query output was saved"));
                } catch (RuntimeException error) {
                    warnings.add("Statement " + result.statementIndex() + " executed but its full result could not be saved. "
                            + "Do not replay a batch that can write. " + Objects.toString(error.getMessage(), "Output storage failed"));
                }
            }
            referenced.add(AgentChartConverter.result2reference(result, id));
        }
        var output = AgentChartConverter.results2response(response, referenced);
        return new DbAgentDatabaseResponse<>(output.ok(), output.scope(), output.data(), output.page(),
                output.error(), output.nextAction(), List.copyOf(warnings));
    }

    @Override
    public AiAgentChart render(AiAgentChartRenderRequest request, AgentToolExecutionContext context) {
        requireActive(context);
        var savedOutput = results.output(context.sessionId(), request.resultId(), context.userId());
        if (savedOutput != null && !savedOutput.complete()) {
            throw invalid("INCOMPLETE_SAVED_RESULT", "resultId", "Only partial query output was saved. Read the available file or request a smaller result before rendering.");
        }
        DbAgentQueryResult source = results.get(context.sessionId(), request.resultId(), context.userId());
        if (source == null) throw invalid("RESULT_NOT_FOUND", "resultId", "Use a resultId returned by db_query in this conversation.");
        AiAgentChartType type;
        try {
            type = AiAgentChartType.from(request.chartType());
        } catch (IllegalArgumentException error) {
            throw invalid("INVALID_CHART_TYPE", "chartType", error.getMessage());
        }
        if (source.data().rows().isEmpty()) throw invalid("NO_DATA", "resultId", "The query returned no rows. Do not fabricate chart data.");
        if (source.data().cellWarnings() != null && !source.data().cellWarnings().isEmpty()) {
            throw invalid("INCOMPLETE_VALUES", "resultId", "Some query values were shortened or unavailable. Query complete values before rendering.");
        }
        Map<String, Boolean> fields = fields(request, type, source.data().rows().size());
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (String field : fields.keySet()) {
            int found = -1;
            for (int i = 0; i < source.data().columns().size(); i++) {
                if (!field.equals(source.data().columns().get(i).name())) continue;
                if (found >= 0) throw invalid("AMBIGUOUS_FIELD", "resultId", "Column " + field + " occurs more than once; give columns distinct SQL aliases.");
                found = i;
            }
            if (found < 0) throw invalid("FIELD_NOT_FOUND", field, "Column " + field + " is not present in the query result.");
            indexes.put(field, found);
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (List<String> row : source.data().rows()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (var field : fields.entrySet()) {
                String value = row.get(indexes.get(field.getKey()));
                values.put(field.getKey(), field.getValue() ? numeric(value, field.getKey(), type) : value);
            }
            data.add(values);
        }
        for (var field : fields.entrySet()) {
            if (field.getValue() && data.stream().allMatch(row -> row.get(field.getKey()) == null)) {
                throw invalid("NO_NUMERIC_VALUES", field.getKey(), "The numeric field contains only SQL NULL values.");
            }
        }
        validateGroups(request, type, data);
        AiAgentChart chart = AgentChartConverter.request2chart(UUID.randomUUID().toString(), request, source, context, data);
        try {
            if (json.writeValueAsBytes(chart).length > 512 * 1024) {
                throw invalid("CHART_TOO_LARGE", "resultId", "Selected chart data exceeds 512 KiB. Choose smaller labels or aggregate data in SQL; the saved query output remains available.");
            }
        } catch (JsonProcessingException error) {
            throw invalid("CHART_ENCODING_ERROR", "resultId", "Cannot encode the selected chart data.");
        }
        requireActive(context);
        context.eventSink().emit(new AgentRuntimeEvent(UUID.randomUUID().toString(), context.sessionId(), context.runId(),
                AgentEventType.CHART_CREATED, Map.of("chart", chart), LocalDateTime.now()));
        return chart;
    }

    private void requireActive(AgentToolExecutionContext context) {
        if (!context.active().getAsBoolean()) throw invalid("RUN_CANCELLED", null, "The Agent run has stopped; no chart was created.");
    }

    private Map<String, Boolean> fields(AiAgentChartRenderRequest request, AiAgentChartType type, int rows) {
        Map<String, Boolean> fields = new LinkedHashMap<>();
        if (type != AiAgentChartType.STATISTICS) {
            requireField(request.xField(), "xField");
            fields.put(request.xField(), type == AiAgentChartType.SCATTER);
        } else if (rows != 1) {
            throw invalid("EXPECTED_SINGLE_ROW", "resultId", "Statistics requires exactly one row; aggregate the metric in SQL first.");
        }
        if (type == AiAgentChartType.COMBO) {
            if (request.series() == null || request.series().isEmpty()) {
                throw invalid("MISSING_SERIES", "series", "Combo requires at least one series with field, chartType and axisPosition.");
            }
            if (request.series().size() > 8) {
                throw invalid("INVALID_ARGUMENT", "series", "Combo supports at most 8 numeric metrics.");
            }
            for (var series : request.series()) {
                if (series == null || series.chartType() == null || series.axisPosition() == null
                        || !List.of("Column", "Line", "AreaLine", "Scatter").contains(series.chartType())
                        || !List.of("left", "right").contains(series.axisPosition())) {
                    throw invalid("INVALID_ARGUMENT", "series", "Each Combo series needs chartType Column, Line, AreaLine or Scatter and axisPosition left or right.");
                }
                requireField(series.field(), "series.field");
                if (fields.putIfAbsent(series.field(), true) != null) {
                    throw invalid("DUPLICATE_FIELD", "series", "Each series must use a distinct field from xField and other series.");
                }
            }
        } else {
            requireField(request.yField(), "yField");
            if (fields.putIfAbsent(request.yField(), true) != null) {
                throw invalid("DUPLICATE_FIELD", "yField", "xField and yField must be different columns.");
            }
            if (request.series() != null && !request.series().isEmpty()) {
                throw invalid("UNEXPECTED_SERIES", "series", "series is only supported for Combo charts.");
            }
        }
        if (!request.groupBy().isEmpty() && !Set.of(AiAgentChartType.COLUMN, AiAgentChartType.BAR,
                AiAgentChartType.LINE, AiAgentChartType.AREA_LINE, AiAgentChartType.SCATTER,
                AiAgentChartType.COMBO).contains(type)) {
            throw invalid("UNSUPPORTED_GROUPING", "groupBy", "groupBy supports Column, Bar, Line, AreaLine, Scatter and Combo. Choose one of these types or omit groupBy.");
        }
        if (request.groupBy().size() > 3) {
            throw invalid("INVALID_ARGUMENT", "groupBy", "Use at most 3 distinct groupBy columns.");
        }
        for (String group : request.groupBy()) {
            if (group == null || group.isBlank() || group.length() > 256) {
                throw invalid("INVALID_ARGUMENT", "groupBy", "Each groupBy item must be a nonblank column name of at most 256 characters.");
            }
            if (group.equals(request.yField()) || fields.putIfAbsent(group, false) != null) {
                throw invalid("DUPLICATE_FIELD", "groupBy", "groupBy columns must be distinct from xField, yField, all metrics and each other.");
            }
        }
        if (request.stack()) {
            boolean stackable = type == AiAgentChartType.COLUMN || type == AiAgentChartType.BAR
                    || type == AiAgentChartType.AREA_LINE || (type == AiAgentChartType.COMBO
                    && request.series().stream().anyMatch(item -> "Column".equals(item.chartType()) || "AreaLine".equals(item.chartType())));
            if (!stackable) {
                throw invalid("UNSUPPORTED_STACK", "stack", "stack supports Column, Bar and AreaLine, or Combo containing a Column or AreaLine metric. Choose a supported type or omit stack.");
            }
        }
        return fields;
    }

    private void validateGroups(AiAgentChartRenderRequest request, AiAgentChartType type,
            List<Map<String, Object>> data) {
        if (request.groupBy().isEmpty() && !request.stack()) return;
        Set<List<Object>> groups = new HashSet<>();
        Set<List<Object>> categories = new HashSet<>();
        int metricCount = type == AiAgentChartType.COMBO ? request.series().size() : 1;
        for (Map<String, Object> row : data) {
            // Tuple values retain SQL NULL, empty strings and delimiter-containing labels without collisions.
            List<Object> group = request.groupBy().stream().map(row::get).toList();
            groups.add(group);
            if (groups.size() * metricCount > 32) {
                throw invalid("TOO_MANY_SERIES", "groupBy", "The chart would exceed 32 derived series (distinct groups multiplied by metrics). Filter groups or reduce metrics in SQL; series are never silently dropped.");
            }
            if (type != AiAgentChartType.SCATTER) {
                List<Object> category = new ArrayList<>(group);
                category.add(row.get(request.xField()));
                if (!categories.add(category)) {
                    throw invalid("DUPLICATE_CATEGORY", "resultId", "More than one row has the same xField + groupBy values. Aggregate metrics with SQL GROUP BY xField and every groupBy column before rendering.");
                }
            }
        }
    }

    private void requireField(String value, String name) {
        if (value == null || value.isBlank()) throw invalid("MISSING_FIELD", name, name + " is required for this chart type.");
    }

    private Object numeric(String value, String field, AiAgentChartType type) {
        if (value == null) return null;
        BigDecimal number;
        try {
            number = new BigDecimal(value);
        } catch (NumberFormatException error) {
            throw invalid("NON_NUMERIC_FIELD", field, "Column " + field + " contains a non-numeric value; use a numeric SQL expression.");
        }
        double plotted = number.doubleValue();
        if (!Double.isFinite(plotted) || BigDecimal.valueOf(plotted).compareTo(number) != 0) {
            throw invalid("NUMERIC_PRECISION", field, "Column " + field + " cannot be represented accurately by the chart. Scale or round it explicitly in SQL.");
        }
        if ((type == AiAgentChartType.PIE || type == AiAgentChartType.RING_PIE || type == AiAgentChartType.ROSE_PIE)
                && number.signum() < 0) {
            throw invalid("NEGATIVE_PIE_VALUE", field, "Pie chart values must be nonnegative; use a bar or line chart for signed values.");
        }
        return number;
    }

    private AgentChartException invalid(String code, String field, String message) {
        return new AgentChartException(code, field, message);
    }
}
