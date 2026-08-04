package ai.chat2db.plugin.xugudb.value;

import ai.chat2db.community.domain.api.model.value.SQLDataValue;
import ai.chat2db.plugin.xugudb.identifier.XugudbIdentifierProcessor;
import ai.chat2db.spi.DefaultValueProcessor;
import ai.chat2db.spi.model.value.JDBCDataValue;

/**
 * Preserves backslashes in XuguDB string literals while doubling single quotes.
 */
public class XugudbValueProcessor extends DefaultValueProcessor {

    @Override
    public String convertSQLValueByType(SQLDataValue dataValue) {
        return XugudbIdentifierProcessor.INSTANCE.quoteStringLiteral(dataValue.getValue());
    }

    @Override
    public String convertJDBCValueStrByType(JDBCDataValue dataValue) {
        String value = dataValue.getString();
        return value == null ? "NULL" : XugudbIdentifierProcessor.INSTANCE.quoteStringLiteral(value);
    }
}
