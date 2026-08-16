package ai.chat2db.plugin.postgresql.value.factory;

import ai.chat2db.plugin.postgresql.enums.type.PostgreSQLColumnTypeEnum;
import ai.chat2db.plugin.postgresql.value.sub.*;
import ai.chat2db.spi.DefaultValueProcessor;

import java.util.Map;


public class PostgreSQLValueProcessorFactory {

    private static final Map<String, DefaultValueProcessor> PROCESSOR_MAP;

    static {
        PostgreSQLNumericProcessor postgreSQLNumericProcessor = new PostgreSQLNumericProcessor();
        PostgreSQLGeometryProcessor postgreSQLGeometryProcessor = new PostgreSQLGeometryProcessor();
        PROCESSOR_MAP = Map.ofEntries(
                Map.entry(PostgreSQLColumnTypeEnum.DECIMAL.name(), postgreSQLNumericProcessor),
                Map.entry(PostgreSQLColumnTypeEnum.NUMERIC.name(), postgreSQLNumericProcessor),
                Map.entry(PostgreSQLColumnTypeEnum.MONEY.name(), new PostgreSQLMoneyProcessor()),
                Map.entry(PostgreSQLColumnTypeEnum.TEXT.name(), new PostgreSQLTextProcessor()),
                Map.entry(PostgreSQLColumnTypeEnum.BYTEA.name(), new PostgreSQLByteaProcessor()),
                Map.entry(PostgreSQLColumnTypeEnum.BOOL.name(), new PostgresBooleanProcessor()),
                Map.entry(PostgreSQLColumnTypeEnum.BIT.name(), new PostgreSQLBitProcessor()),
                Map.entry(PostgreSQLColumnTypeEnum.TIMESTAMP.name(), new PostgresTimeStampProcessor()),
                Map.entry(PostgreSQLColumnTypeEnum.TIMESTAMPTZ.name(), new PostgresTimeStampTZProcessor()),
                Map.entry(PostgreSQLColumnTypeEnum.TIMETZ.name(), new PostgresTimeTZProcessor()),
                Map.entry(PostgreSQLGeometryProcessor.GEOMETRY_TYPE, postgreSQLGeometryProcessor),
                Map.entry(PostgreSQLGeometryProcessor.GEOGRAPHY_TYPE, postgreSQLGeometryProcessor)

        );
    }

    public static DefaultValueProcessor getValueProcessor(String type) {
        return PROCESSOR_MAP.get(type);
    }
}
