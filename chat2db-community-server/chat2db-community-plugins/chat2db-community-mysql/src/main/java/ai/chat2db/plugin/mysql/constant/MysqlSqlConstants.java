package ai.chat2db.plugin.mysql.constant;

import ai.chat2db.spi.constant.SQLConstants;

public final class MysqlSqlConstants {

    public static final int CREATE_VIEW_SQL_CAPACITY = 100;
    public static final int LIMIT_SQL_EXTRA_CAPACITY = 14;
    public static final String ALL_DATABASE_ALL_TABLE_SCOPE = "*.*";
    public static final String ALL_TABLE_SCOPE_SUFFIX = ".*";
    public static final String PREVIOUS_COLUMN_NOT_FOUND = "-1";
    public static final String SQL_ACCOUNT_LOCK = " ACCOUNT LOCK";
    public static final String SQL_ACCOUNT_UNLOCK = " ACCOUNT UNLOCK";
    public static final String SQL_ALGORITHM = "ALGORITHM = ";
    public static final String SQL_AFTER = " AFTER ";
    public static final String SQL_ALTER_USER = "ALTER USER ";
    public static final String SQL_AUTO_INCREMENT_ASSIGNMENT = "AUTO_INCREMENT=";
    public static final String SQL_COLLATE_ASSIGNMENT = "COLLATE=";
    public static final String SQL_COMMENT = "COMMENT=";
    public static final String SQL_COMMENT_KEYWORD = "COMMENT";
    public static final String SQL_COMMENT_SPACE_SINGLE_QUOTE = "COMMENT '";
    public static final String SQL_COMMENT_WITH_SINGLE_QUOTE = " COMMENT='";
    public static final String SQL_COPY_TABLE_DATA_TEMPLATE = "CREATE TABLE %s AS SELECT * FROM %s";
    public static final String SQL_COPY_TABLE_STRUCTURE_TEMPLATE = "CREATE TABLE %s AS SELECT * FROM %s WHERE 1=0";
    public static final String SQL_CREATE = "create ";
    public static final String SQL_CREATE_USER = "CREATE USER ";
    public static final String SQL_DEFAULT_CHARACTER_SET_ASSIGNMENT = "DEFAULT CHARACTER SET=";
    public static final String SQL_DEFINER = "DEFINER = ";
    public static final String SQL_DROP = "DROP ";
    public static final String SQL_DROP_COLUMN_BACK_QUOTE = "DROP COLUMN `";
    public static final String SQL_DROP_DATABASE_TEMPLATE = SQLConstants.DROP_DATABASE_SQL_PREFIX + "%s";
    public static final String SQL_DROP_INDEX_BACK_QUOTE = "DROP INDEX `";
    public static final String SQL_DROP_PRIMARY_KEY = "DROP PRIMARY KEY";
    public static final String SQL_DROP_PROCEDURE_TEMPLATE = "DROP PROCEDURE %s";
    public static final String SQL_DROP_TABLE_TEMPLATE = "DROP TABLE %s";
    public static final String SQL_DROP_USER = "DROP USER ";
    public static final String SQL_DROP_VIEW_TEMPLATE = "DROP VIEW %s";
    public static final String SQL_INVISIBLE = "INVISIBLE";
    public static final String SQL_VISIBLE = "VISIBLE";
    public static final String SQL_ALTER_INDEX = "ALTER INDEX ";
    public static final String SQL_ENGINE_ASSIGNMENT = "ENGINE=";
    public static final String SQL_FIRST_TERMINATOR = " FIRST;\n";
    public static final String SQL_FROM = " FROM ";
    public static final String SQL_GRANT = "GRANT ";
    public static final String SQL_IDENTIFIED_BY = " IDENTIFIED BY ";
    public static final String SQL_LIMIT_ONE_SUFFIX = " LIMIT 1";
    public static final String SQL_MODIFY_COLUMN = " MODIFY COLUMN ";
    public static final String SQL_PARTITION_SEPARATOR = " \n";
    public static final String SQL_RENAME = "RENAME TO ";
    public static final String SQL_REVOKE = "REVOKE ";
    public static final String SQL_SECURITY = "SQL SECURITY ";
    public static final String SQL_SELECT_ACCOUNT_LOCKED_MYSQL_USER = "SELECT account_locked FROM mysql.user LIMIT 1";
    public static final String SQL_SELECT_DATABASE_TEMPLATE = "USE %s;";
    public static final String SQL_SELECT_USER_HOST_MYSQL_USER = "SELECT User, Host FROM mysql.user LIMIT 1";
    public static final String SQL_SET_FOREIGN_KEY_CHECKS_DISABLED = "SET FOREIGN_KEY_CHECKS=0;";
    public static final String SQL_SET_FOREIGN_KEY_CHECKS_ENABLED = "SET FOREIGN_KEY_CHECKS=1;";
    public static final String SQL_SHOW_CREATE_FUNCTION = "SHOW CREATE FUNCTION ";
    public static final String SQL_SHOW_CREATE_FUNCTION_TEMPLATE = "SHOW CREATE FUNCTION %s;";
    public static final String SQL_SHOW_CREATE_PROCEDURE = "SHOW CREATE PROCEDURE ";
    public static final String SQL_SHOW_CREATE_PROCEDURE_TEMPLATE = "show create procedure %s ";
    public static final String SQL_SHOW_CREATE_TABLE_TEMPLATE = "show create table %s ";
    public static final String SQL_SHOW_CREATE_TRIGGER_TEMPLATE = "show create trigger %s ";
    public static final String SQL_SHOW_CREATE_VIEW_TEMPLATE = "show create view %s ";
    public static final String SQL_SHOW_GRANTS_FOR = "SHOW GRANTS FOR ";
    public static final String SQL_SHOW_INDEX_FROM = "SHOW INDEX FROM ";
    public static final String SQL_ANALYZE_TABLE = "ANALYZE TABLE ";
    public static final String SQL_OPTIMIZE_TABLE = "OPTIMIZE TABLE ";
    public static final String SQL_CHECK_TABLE = "CHECK TABLE ";
    public static final String SQL_REPAIR_TABLE = "REPAIR TABLE ";
    public static final String SQL_SHOW_PROCEDURE_STATUS = "SHOW PROCEDURE STATUS WHERE Db = DATABASE()";
    public static final String SQL_SHOW_TRIGGERS = "SHOW TRIGGERS";
    public static final String SQL_TRUNCATE_TABLE_TEMPLATE = "TRUNCATE TABLE %s";
    public static final String SQL_UNDEFINED = "undefined";
    public static final String SQL_WITH_GRANT_OPTION = " WITH GRANT OPTION";

    private MysqlSqlConstants() {
    }
}
