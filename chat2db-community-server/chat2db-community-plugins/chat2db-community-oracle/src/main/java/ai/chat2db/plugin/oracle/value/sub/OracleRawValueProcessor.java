package ai.chat2db.plugin.oracle.value.sub;

import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.plugin.oracle.OracleSqlGuards;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;


public class OracleRawValueProcessor extends DefaultValueProcessor {


    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return EasyStringUtils.quoteString(OracleSqlGuards.normalizeHexLiteral(
                dataValue.getValue(), dataValue.getBlobHexString()));
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        return dataValue.getBinaryDataString();
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return EasyStringUtils.quoteString(dataValue.getBlobHexString());
    }
}
