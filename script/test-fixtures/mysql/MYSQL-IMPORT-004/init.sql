-- MYSQL-IMPORT-004: SQL-file encoding, error, and transaction options
-- Test fixture: target tables, a non-transactional MyISAM table, and an admin user.

CREATE DATABASE IF NOT EXISTS `import004_test`;
USE `import004_test`;

CREATE TABLE IF NOT EXISTS `import004_items` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `amount` DECIMAL(10,2) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `import004_myisam` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `value` VARCHAR(32) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=MyISAM;

CREATE USER IF NOT EXISTS 'import004_admin'@'%' IDENTIFIED BY 'Import004_admin_2026';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP ON `import004_test`.* TO 'import004_admin'@'%';
