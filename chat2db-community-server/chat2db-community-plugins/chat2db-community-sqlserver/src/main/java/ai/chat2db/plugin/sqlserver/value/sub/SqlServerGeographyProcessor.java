package ai.chat2db.plugin.sqlserver.value.sub;

import ai.chat2db.plugin.sqlserver.value.template.SqlServerDmlValueTemplate;
import ai.chat2db.plugin.sqlserver.identifier.SqlServerIdentifierProcessor;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;


public class SqlServerGeographyProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        String value = dataValue.getValue();
        if (value.startsWith("0x")) {
            return value;
        }
        return SqlServerDmlValueTemplate.wrapGeography(SqlServerIdentifierProcessor.INSTANCE.escapeString(value));
    }

    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return SqlServerDmlValueTemplate.wrapBinary(dataValue.getBlobHexString());
    }

    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return SqlServerDmlValueTemplate.wrapBinary(dataValue.getBlobHexString());
    }
}
