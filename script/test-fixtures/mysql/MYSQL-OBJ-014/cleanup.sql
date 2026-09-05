DROP TABLE IF EXISTS chat2db_mysql_obj_014.ts_partitioned_orders;
DROP TABLE IF EXISTS chat2db_mysql_obj_014.ts_orders_migrated;
DROP TABLE IF EXISTS chat2db_mysql_obj_014.ts_orders;
DROP TABLESPACE chat2db_ts_general ENGINE = InnoDB;
DROP TABLESPACE chat2db_ts_empty ENGINE = InnoDB;
DROP DATABASE IF EXISTS chat2db_mysql_obj_014;
DROP USER IF EXISTS 'chat2db_obj014'@'%';
