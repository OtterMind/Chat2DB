package ai.chat2db.community.web.api.enums.ai;

import ai.chat2db.community.tools.enums.IBaseEnum;
import lombok.Getter;


@Getter
public enum QuestionTypeEnum implements IBaseEnum<String> {


    NL_2_SQL("Convert natural language to a SQL query and return only the SQL statement without extra explanation."),


    SQL_EXPLAIN("Explain SQL"),


    SQL_DEBUG("SQL DEBUG"),


    SQL_DEBUG_CHAIN("SQL DEBUG and regenerate sql"),


    ORDINARY_CHAT("General drawer chat"),


    NL_2_SQL_CHAIN("Convert natural language to a SQL query with chain-of-thought reasoning"),


    SQL_OPTIMIZER("Provide optimization suggestions"),


    SQL_2_SQL("Convert SQL"),


    FILTER_GENERATION("Generate filters"),


    @Deprecated
    CRUD_GENERATION("Generate CRUD"),


    @Deprecated
    DASHBOARD_GENERATION("Generate dashboard"),


    @Deprecated
    EXCEL_CHAT("Excel chat"),


    @Deprecated
    SQL_PROMPT("SQL prompt"),


    @Deprecated
    TEXT_TO_CREATE_TABLE("Create table from text"),


    TEXT_TO_CREATE_TABLE_STREAM("Create stream from text"),


    TEXT_TO_TRANSLATE_COLUMN("Translate columns from text"),


    TEXT_MODIFY_COLUMN("Modify columns from text"),


    @Deprecated
    TEXT_TO_TRANSLATE_COLUMN_V1("Translate columns from text"),


    @Deprecated
    DASHBOARD_GENERATION_STREAM("Convert natural language to a SQL query and return only the SQL statement without extra explanation."),


    DASHBOARD_GENERATION_CHAIN("Generate dashboard with chain-of-thought reasoning"),


    HEADER_TO_DASHBOARD("Generate dashboard from headers"),


    NO_SQL_DATABASE("NoSQL database chat"),
    ;

    final String description;

    QuestionTypeEnum(String description) {
        this.description = description;
    }

    @Override
    public String getCode() {
        return this.name();
    }


    public static QuestionTypeEnum getByCode(String code) {
        for (QuestionTypeEnum promptTypeEnum : QuestionTypeEnum.values()) {
            if (promptTypeEnum.getCode().equals(code)) {
                return promptTypeEnum;
            }
        }
        return null;
    }
}
