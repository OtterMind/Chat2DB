-- MYSQL-IMPORT-001: Import preview and column mapping
-- Test fixture: target tables (one with a NOT NULL no-default column) and an admin user.

CREATE DATABASE IF NOT EXISTS `import001_test`;
USE `import001_test`;

CREATE TABLE IF NOT EXISTS `import001_contacts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `email` VARCHAR(128) DEFAULT NULL,
    `age` INT DEFAULT NULL,
    `note` VARCHAR(255) DEFAULT 'imported',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `import001_strict` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(32) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE USER IF NOT EXISTS 'import001_admin'@'%' IDENTIFIED BY 'Import001_admin_2026';
GRANT SELECT, INSERT, UPDATE, DELETE ON `import001_test`.* TO 'import001_admin'@'%';
