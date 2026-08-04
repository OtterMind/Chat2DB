package ai.chat2db.plugin.oracle.value.sub;

import ai.chat2db.community.tools.util.EasyStringUtils;
import ai.chat2db.plugin.oracle.OracleSqlGuards;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;

import java.util.Objects;


public class OracleLongRawProcessor extends DefaultValueProcessor {

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
        String blobHexString = dataValue.getBlobHexString();
        if (Objects.isNull(blobHexString)) {
            return "NULL";
        }
        return EasyStringUtils.quoteString(blobHexString);
    }

}
