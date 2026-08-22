-- MYSQL-IMPORT-002: CSV encoding and format options
-- Test fixture: import target table and an admin user.

CREATE DATABASE IF NOT EXISTS `import002_test`;
USE `import002_test`;

CREATE TABLE IF NOT EXISTS `import002_items` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `note` VARCHAR(255) DEFAULT NULL,
    `price` DECIMAL(10,2) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE USER IF NOT EXISTS 'import002_admin'@'%' IDENTIFIED BY 'Import002_admin_2026';
GRANT SELECT, INSERT, UPDATE, DELETE ON `import002_test`.* TO 'import002_admin'@'%';
