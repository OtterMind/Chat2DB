CREATE DATABASE IF NOT EXISTS chat2db_mysql_obj_014;
USE chat2db_mysql_obj_014;

DROP TABLE IF EXISTS ts_partitioned_orders;
DROP TABLE IF EXISTS ts_orders_migrated;
DROP TABLE IF EXISTS ts_orders;

CREATE TABLESPACE chat2db_ts_general ADD DATAFILE 'chat2db_ts_general.ibd' ENGINE = InnoDB;
CREATE TABLESPACE chat2db_ts_empty ADD DATAFILE 'chat2db_ts_empty.ibd' FILE_BLOCK_SIZE = 8192 ENGINE = InnoDB;

CREATE TABLE ts_orders (
  id BIGINT NOT NULL PRIMARY KEY,
  note VARCHAR(128)
) ENGINE = InnoDB TABLESPACE chat2db_ts_general;

CREATE TABLE ts_orders_migrated (
  id BIGINT NOT NULL PRIMARY KEY,
  note VARCHAR(128)
) ENGINE = InnoDB;

ALTER TABLE ts_orders_migrated TABLESPACE chat2db_ts_general;
