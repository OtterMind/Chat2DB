package ai.chat2db.plugin.dm.value.sub;

import ai.chat2db.plugin.dm.DMSqlGuards;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;

import java.util.Objects;


public class DMBitProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return DMSqlGuards.requireBitLiteral(dataValue.getValue());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        Object object = dataValue.getObject();
        if (Objects.isNull(object)) {
            return null;
        }
        return String.valueOf(dataValue.getInt());
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return convertJDBCValueByType(dataValue);
    }
}
