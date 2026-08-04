package ai.chat2db.plugin.oracle.value.sub;

import ai.chat2db.plugin.oracle.identifier.OracleIdentifierProcessor;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;


public class OracleLongProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return OracleIdentifierProcessor.INSTANCE.quoteStringLiteral(dataValue.getValue());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return dataValue.getCharsetString();
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return OracleIdentifierProcessor.INSTANCE.quoteStringLiteral(dataValue.getCharsetString());
    }
}
