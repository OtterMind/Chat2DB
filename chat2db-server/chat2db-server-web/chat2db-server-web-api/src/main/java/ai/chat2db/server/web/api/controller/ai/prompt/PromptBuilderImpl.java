package ai.chat2db.server.web.api.controller.ai.prompt;

import ai.chat2db.server.web.api.controller.ai.enums.PromptType;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 提示词构建器实现
 */
@Component
public class PromptBuilderImpl implements PromptBuilder {

    private static final int MAX_HISTORY_MESSAGES = 6;
    private static final int MAX_HISTORY_CONTENT_LENGTH = 1200;
    private static final int MAX_PREVIOUS_SQL_LENGTH = 6000;

    private final PromptTemplateRegistry templateRegistry;
    private final PromptValidator validator;

    private PromptContext context;

    @Autowired
    public PromptBuilderImpl(PromptTemplateRegistry templateRegistry, PromptValidator validator) {
        this.templateRegistry = templateRegistry;
        this.validator = validator;
    }

    @Override
    public PromptBuilder context(PromptContext context) {
        this.context = context;
        return this;
    }

    @Override
    public PromptBuilder message(String message) {
        if (this.context == null) {
            this.context = new PromptContext();
        }
        this.context.setMessage(message);
        return this;
    }

    @Override
    public PromptBuilder ext(String ext) {
        if (this.context == null) {
            this.context = new PromptContext();
        }
        this.context.setExt(ext);
        return this;
    }

    @Override
    public PromptBuilder schema(String schemaDdl) {
        if (this.context == null) {
            this.context = new PromptContext();
        }
        this.context.setSchemaDdl(schemaDdl);
        return this;
    }

    @Override
    public PromptBuilder explainPlan(String explainPlan) {
        if (this.context == null) {
            this.context = new PromptContext();
        }
        this.context.setExplainPlan(explainPlan);
        return this;
    }

    @Override
    public PromptBuilder dataSourceType(String dataSourceType) {
        if (this.context == null) {
            this.context = new PromptContext();
        }
        this.context.setDataSourceType(dataSourceType);
        return this;
    }

    @Override
    public PromptBuilder targetSqlType(String targetSqlType) {
        if (this.context == null) {
            this.context = new PromptContext();
        }
        this.context.setTargetSqlType(targetSqlType);
        return this;
    }

    @Override
    public PromptBuilder sourceFields(String sourceFields) {
        if (this.context == null) {
            this.context = new PromptContext();
        }
        this.context.setSourceFields(sourceFields);
        return this;
    }

    @Override
    public String build() {
        validateContext();

        PromptType type = context.getPromptType();
        if (type == null) {
            type = PromptType.NL_2_SQL;
        }

        PromptTemplate template = templateRegistry.getTemplate(type);
        String builtPrompt = fillTemplate(template, context);

        return validator.cleanPrompt(builtPrompt);
    }

    @Override
    public boolean validate() {
        String builtPrompt = build();
        return validator.isValidLength(builtPrompt);
    }

    private void validateContext() {
        if (context == null) {
            throw new IllegalStateException("PromptContext is null");
        }
        if (StringUtils.isBlank(context.getMessage())
                && !Objects.equals(context.getPromptType(), PromptType.NL_2_COMMENT)
                && !Objects.equals(context.getPromptType(), PromptType.NL_2_COMMENT_BATCH)
                && !Objects.equals(context.getPromptType(), PromptType.NL_2_FIELD_MAPPING)
                && !Objects.equals(context.getPromptType(), PromptType.NL_2_DATA_EXPRESSION)
                && !Objects.equals(context.getPromptType(), PromptType.SQL_FIX)) {
            throw new IllegalArgumentException("Message is required");
        }
    }

    private String fillTemplate(PromptTemplate template, PromptContext context) {
        String templateStr = template.getTemplate();
        String description = context.getPromptType() != null
                ? context.getPromptType().getDescription()
                : "将自然语言转换成 SQL 查询";

        String filledTemplate = templateStr
                .replace("{description}", description)
                .replace("{ext}", Objects.toString(context.getExt(), ""))
                .replace("{db_type}", Objects.toString(context.getDataSourceType(), "MYSQL"))
                .replace("{schema}", Objects.toString(context.getSchemaDdl(), ""))
                .replace("{explain_plan}", Objects.toString(context.getExplainPlan(), ""))
                .replace("{message}", Objects.toString(context.getMessage(), ""))
                .replace("{target_sql_type}", Objects.toString(context.getTargetSqlType(),
                        Objects.toString(context.getDataSourceType(), "MYSQL")));

        // 处理 source_fields 占位符（用于字段映射）
        if (filledTemplate.contains("{source_fields}")) {
            String sourceFieldsText = formatSourceFields(context.getSourceFields());
            filledTemplate = filledTemplate.replace("{source_fields}", sourceFieldsText);
        }

        // 处理 SQL_FIX 的占位符
        if (filledTemplate.contains("{error_message}") || filledTemplate.contains("{original_sql}")) {
            String errorMessage = "";
            String originalSql = "";
            if (StringUtils.isNotBlank(context.getExt())) {
                try {
                    com.alibaba.fastjson2.JSONObject extJson = com.alibaba.fastjson2.JSON.parseObject(context.getExt());
                    errorMessage = extJson.getString("errorMessage");
                    originalSql = extJson.getString("originalSql");
                } catch (Exception e) {
                    // 忽略解析错误
                }
            }
            filledTemplate = filledTemplate.replace("{error_message}", Objects.toString(errorMessage, ""));
            filledTemplate = filledTemplate.replace("{original_sql}", Objects.toString(originalSql, ""));
        }

        return appendRevisionContextIfNeeded(filledTemplate, context);
    }

    private String appendRevisionContextIfNeeded(String filledTemplate, PromptContext context) {
        if (context.getPromptType() != PromptType.NL_2_SQL
                || !Boolean.TRUE.equals(context.getIsRevision())
                || StringUtils.isBlank(context.getPreviousSql())) {
            return filledTemplate;
        }

        StringBuilder builder = new StringBuilder(filledTemplate);
        builder.append("\n\n")
                .append("### 连续对话修正要求\n")
                .append("用户正在基于上一版 SQL 提出修正。请结合对话历史、上一版 SQL、当前表结构和当前 SQL input，")
                .append("生成一条完整的新 SQL。\n")
                .append("- 不要只描述变更点。\n")
                .append("- 不要省略未变化的 SELECT、FROM、JOIN、WHERE、GROUP BY、ORDER BY 等部分。\n")
                .append("- 优先使用 ```sql 代码块输出最终 SQL。\n");

        String formattedHistory = formatConversationHistory(context.getHistory());
        if (StringUtils.isNotBlank(formattedHistory)) {
            builder.append("\n### 最近对话历史\n")
                    .append(formattedHistory)
                    .append("\n");
        }

        builder.append("\n### 上一版 SQL\n")
                .append("```sql\n")
                .append(truncate(context.getPreviousSql(), MAX_PREVIOUS_SQL_LENGTH))
                .append("\n```\n");

        return builder.toString();
    }

    private String formatConversationHistory(String history) {
        if (StringUtils.isBlank(history)) {
            return "";
        }
        try {
            JSONArray messages = JSON.parseArray(history);
            int start = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
            StringBuilder builder = new StringBuilder();
            for (int i = start; i < messages.size(); i++) {
                JSONObject message = messages.getJSONObject(i);
                String role = Objects.toString(message.getString("role"), "unknown");
                String content = truncate(
                        Objects.toString(message.getString("content"), ""),
                        MAX_HISTORY_CONTENT_LENGTH);
                if (StringUtils.isNotBlank(content)) {
                    builder.append(role).append(": ").append(content).append("\n");
                }
            }
            return builder.toString().trim();
        } catch (Exception e) {
            return truncate(history, MAX_HISTORY_CONTENT_LENGTH * 2);
        }
    }

    private String truncate(String text, int maxLength) {
        if (StringUtils.isBlank(text) || text.length() <= maxLength) {
            return Objects.toString(text, "");
        }
        return text.substring(0, maxLength) + "\n...已截断...";
    }

    private String formatSourceFields(String sourceFieldsJson) {
        if (StringUtils.isBlank(sourceFieldsJson)) {
            return "";
        }
        try {
            if (sourceFieldsJson.trim().startsWith("[")) {
                com.alibaba.fastjson2.JSONArray fields = com.alibaba.fastjson2.JSON.parseArray(sourceFieldsJson);
                return fields.stream()
                        .map(field -> "- " + field.toString())
                        .collect(java.util.stream.Collectors.joining("\n"));
            }
            return sourceFieldsJson;
        } catch (Exception e) {
            return sourceFieldsJson;
        }
    }
}
