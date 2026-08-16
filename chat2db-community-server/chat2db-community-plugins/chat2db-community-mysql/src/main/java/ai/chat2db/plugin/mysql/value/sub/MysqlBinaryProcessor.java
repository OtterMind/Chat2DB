package ai.chat2db.plugin.mysql.value.sub;

import ai.chat2db.plugin.mysql.MysqlSqlGuards;
import ai.chat2db.plugin.mysql.value.template.MysqlDmlValueTemplate;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;


public class MysqlBinaryProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        String value = dataValue.getValue();
        if (MysqlSqlGuards.isHexLiteral(value)) {
            return value;
        }
        return MysqlDmlValueTemplate.wrapHex(dataValue.getBlobHexString());
    }


    @Override
    public String convertJDBCValueByType(JDBCDataValue dataValue) {
        byte[] bytes = dataValue.getBytes();
        if (bytes.length == 1) {
            if (bytes[0] >= 32 && bytes[0] <= 126) {
                return new String(bytes);
            }
        }
        return MysqlDmlValueTemplate.wrapHex(dataValue.getBlobHexString());
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return MysqlDmlValueTemplate.wrapHex(dataValue.getBlobHexString());
    }
}
