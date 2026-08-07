-- MYSQL-IMPORT-003: Excel sheet/header/NULL options
-- Test fixture: import target table and an admin user.

CREATE DATABASE IF NOT EXISTS `import003_test`;
USE `import003_test`;

CREATE TABLE IF NOT EXISTS `import003_records` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `amount` DECIMAL(14,2) DEFAULT NULL,
    `record_date` DATE DEFAULT NULL,
    `active` TINYINT(1) DEFAULT NULL,
    `note` VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE USER IF NOT EXISTS 'import003_admin'@'%' IDENTIFIED BY 'Import003_admin_2026';
GRANT SELECT, INSERT, UPDATE, DELETE ON `import003_test`.* TO 'import003_admin'@'%';
