package ai.chat2db.server.web.api.controller.ai.enums;

import ai.chat2db.server.tools.base.enums.BaseEnum;

import lombok.Getter;

/**
 * 提示类型
 *
 * @author moji
 * @version PromptType.java, v 0.1 2023年04月09日 15:36 moji Exp $
 * @date 2023/04/09
 */
@Getter
public enum PromptType implements BaseEnum<String> {

    /**
     * 自然语言转换成SQL
     */
    NL_2_SQL("将自然语言转换成SQL"),

    /**
     * 解释SQL
     */
    SQL_EXPLAIN("解释SQL"),

    /**
     * SQL优化
     */
    SQL_OPTIMIZER("提供优化建议"),

    /**
     * SQL转换
     */
    SQL_2_SQL("进行SQL转换"),

    /**
     * text generation
     */
    TEXT_GENERATION("文本生成"),

    /**
     * 生成标题
     */
    TITLE_GENERATION("生成标题"),

    /**
     * 选择需要查询的表
     */
    SELECT_TABLES("选择需要查询的表"),

    /**
     * 自然语言转换成注释
     */
    NL_2_COMMENT("猜测表和字段注释"),

    NL_2_COMMENT_BATCH("批量猜测表注释"),

    /**
     * 智能字段映射推荐
     */
    NL_2_FIELD_MAPPING("智能字段映射推荐"),

    /**
     * 智能数据生成表达式推荐
     */
    NL_2_DATA_EXPRESSION("智能数据生成表达式推荐"),

    /**
     * SQL错误修复
     */
    SQL_FIX("SQL错误修复"),

    /**
     * SQL 补全
     */
    SQL_COMPLETION("SQL补全"),
    ;

    final String description;

    /**
     * 判断是否为简单任务类型（可以使用快速模型）
     * 简单任务通常不需要复杂的推理，如选表、生成标题等
     *
     * @return true 如果是简单任务
     */
    public boolean isSimpleTask() {
        return this == SELECT_TABLES || this == TITLE_GENERATION || this == NL_2_COMMENT_BATCH
                || this == NL_2_COMMENT || this == NL_2_FIELD_MAPPING || this == NL_2_DATA_EXPRESSION
                || this == SQL_COMPLETION;
    }

    PromptType(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }
}
