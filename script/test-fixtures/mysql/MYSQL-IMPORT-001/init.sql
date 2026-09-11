-- MYSQL-IMPORT-001: Import preview and column mapping
-- Test fixture: target tables (one with a NOT NULL no-default column) and an admin user.

CREATE DATABASE IF NOT EXISTS `import001_test`;
USE `import001_test`;

CREATE TABLE IF NOT EXISTS `import001_contacts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `name` VARCHAR(64) NOT NULL COMMENT 'Contact name',
    `email` VARCHAR(128) DEFAULT NULL COMMENT 'Email address',
    `age` INT DEFAULT NULL COMMENT 'Age',
    `note` VARCHAR(255) DEFAULT 'imported' COMMENT 'Import note',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `import001_strict` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    `code` VARCHAR(32) NOT NULL COMMENT 'Business code',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE USER IF NOT EXISTS 'import001_admin'@'%' IDENTIFIED BY 'Import001_admin_2026';
GRANT SELECT, INSERT, UPDATE, DELETE ON `import001_test`.* TO 'import001_admin'@'%';
