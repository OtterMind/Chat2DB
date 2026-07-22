package ai.chat2db.plugin.oceanbase.oracle;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.plugin.oceanbase.oracle.parser.OceanBaseOracleSqlParser;
import ai.chat2db.spi.ISQLParser;

public class OceanBaseOracleSyntaxPlugin implements ai.chat2db.spi.IStandardSqlEditorHintPlugin {

        @Override
        public String getDatabaseType() {
            return DatabaseTypeEnum.OCEANBASE_ORACLE.name();
        }

        @Override
        public ISQLParser getSQLParser() {
            return new OceanBaseOracleSqlParser();
        }
}
