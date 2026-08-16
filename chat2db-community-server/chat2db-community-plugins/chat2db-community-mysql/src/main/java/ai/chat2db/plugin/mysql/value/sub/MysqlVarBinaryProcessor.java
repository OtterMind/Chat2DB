package ai.chat2db.plugin.mysql.value.sub;

import ai.chat2db.plugin.mysql.MysqlSqlGuards;
import ai.chat2db.plugin.mysql.value.template.MysqlDmlValueTemplate;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;
import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class MysqlVarBinaryProcessor extends DefaultValueProcessor {

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
        return dataValue.getBlobString();
    }


    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        return MysqlDmlValueTemplate.wrapHex(dataValue.getBlobHexString());
    }

}

