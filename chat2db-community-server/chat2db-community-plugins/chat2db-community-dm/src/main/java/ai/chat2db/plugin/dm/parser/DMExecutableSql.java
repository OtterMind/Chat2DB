package ai.chat2db.plugin.dm.parser;

import ai.chat2db.community.domain.api.enums.parser.SqlTypeEnum;

public record DMExecutableSql(String sqlType, String originalSql, String executableSql) {

    public boolean isExplain() {
        return SqlTypeEnum.EXPLAIN.name().equals(sqlType);
    }
}
