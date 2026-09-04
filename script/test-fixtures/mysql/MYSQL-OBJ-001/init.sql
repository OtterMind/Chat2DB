-- MYSQL-OBJ-001: Database and table default character set and collation editing
-- Test fixture: a database created with a known charset/collation and a table inside it.

CREATE DATABASE IF NOT EXISTS `obj001_test` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;
USE `obj001_test`;

CREATE TABLE IF NOT EXISTS `obj001_contacts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;

INSERT INTO `obj001_contacts` (`name`) VALUES ('Alice'), ('Bob');

CREATE USER IF NOT EXISTS 'obj001_admin'@'%' IDENTIFIED BY 'Obj001_admin_2026';
CREATE USER IF NOT EXISTS 'obj001_viewer'@'%' IDENTIFIED BY 'Obj001_viewer_2026';
