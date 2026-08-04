package ai.chat2db.plugin.sqlserver.value.sub;

import ai.chat2db.plugin.sqlserver.value.template.SqlServerDmlValueTemplate;
import ai.chat2db.plugin.sqlserver.identifier.SqlServerIdentifierProcessor;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;


public class SqlServerXmlProcessor extends DefaultValueProcessor {


    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return SqlServerDmlValueTemplate.wrapString(SqlServerIdentifierProcessor.INSTANCE.escapeString(dataValue.getValue()));
    }

    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return dataValue.getStringValue();
    }

    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return SqlServerDmlValueTemplate.wrapString(SqlServerIdentifierProcessor.INSTANCE.escapeString(dataValue.getStringValue()));
    }
}
