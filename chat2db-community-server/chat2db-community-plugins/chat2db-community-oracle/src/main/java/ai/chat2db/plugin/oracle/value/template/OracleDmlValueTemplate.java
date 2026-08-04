package ai.chat2db.plugin.oracle.value.template;

import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;

import static ai.chat2db.plugin.oracle.constant.OracleDmlValueTemplateConstants.*;






public class OracleDmlValueTemplate {




    public static String wrapDate(String date) {
        return String.format(DATE_TEMPLATE, OracleIdentifierProcessor.INSTANCE.escapeString(date));
    }

    public static String wrapTimestamp(String timestamp, int scale) {
        return String.format(TIMESTAMP_TEMPLATE, OracleIdentifierProcessor.INSTANCE.escapeString(timestamp), scale);
    }

    public static String wrapTimestampTz(String timestamp, int scale) {
        return String.format(TIMESTAMP_TZ_TEMPLATE, OracleIdentifierProcessor.INSTANCE.escapeString(timestamp), scale);
    }

    public static String wrapTimestampTzWithOutNanos(String timestamp) {
        return String.format(TIMESTAMP_TZ_WITHOUT_NANOS_TEMPLATE, OracleIdentifierProcessor.INSTANCE.escapeString(timestamp));
    }

    public static String wrapIntervalYearToMonth(String year, int precision) {
        return String.format(INTERVAL_YEAR_TO_MONTH_TEMPLATE, OracleIdentifierProcessor.INSTANCE.escapeString(year), precision);
    }

    public static String wrapIntervalDayToSecond(String day, int precision, int scale) {
        return String.format(INTERVAL_DAY_TO_SECOND_TEMPLATE, OracleIdentifierProcessor.INSTANCE.escapeString(day), precision, scale);
    }

    public static String wrapXml(String xml) {
        return String.format(XML_TEMPLATE, OracleIdentifierProcessor.INSTANCE.escapeString(xml));
    }

}
