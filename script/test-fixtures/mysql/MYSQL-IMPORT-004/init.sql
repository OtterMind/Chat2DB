DROP DATABASE IF EXISTS chat2db_import004;
CREATE DATABASE chat2db_import004 CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE chat2db_import004;

CREATE TABLE import004_innodb (
    id BIGINT PRIMARY KEY,
    value_text VARCHAR(255) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE import004_myisam (
    id BIGINT PRIMARY KEY,
    value_text VARCHAR(255) NOT NULL
) ENGINE=MyISAM;
