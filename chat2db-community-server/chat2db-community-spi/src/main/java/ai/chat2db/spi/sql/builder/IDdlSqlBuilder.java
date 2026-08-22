package ai.chat2db.spi.sql.builder;

public interface IDdlSqlBuilder {

    IDatabaseSqlBuilder database();

    ISchemaSqlBuilder schema();

    ITableSqlBuilder table();

    IViewSqlBuilder view();

    ITablespaceSqlBuilder tablespace();
}
