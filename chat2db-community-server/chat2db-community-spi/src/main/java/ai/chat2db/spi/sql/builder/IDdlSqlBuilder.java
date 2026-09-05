package ai.chat2db.spi.sql.builder;

import ai.chat2db.spi.DefaultSqlBuilder;

public interface IDdlSqlBuilder {

    IDatabaseSqlBuilder database();

    ISchemaSqlBuilder schema();

    ITableSqlBuilder table();

    IViewSqlBuilder view();

    default ITablespaceSqlBuilder tablespace() {
        return new DefaultSqlBuilder();
    }
}
