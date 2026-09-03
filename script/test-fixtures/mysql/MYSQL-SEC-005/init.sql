-- MYSQL-SEC-005: Column-level privilege management
-- Test fixture: database/table with a manager (holds GRANT OPTION) and a limited user.

CREATE DATABASE IF NOT EXISTS `sec005_test`;
USE `sec005_test`;

CREATE TABLE IF NOT EXISTS `sec005_employees` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `salary` DECIMAL(12,2) NOT NULL DEFAULT 0,
    `notes` TEXT,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `sec005_employees` (`name`, `salary`, `notes`) VALUES
    ('Alice', 100000.00, 'internal'),
    ('Bob', 80000.00, 'internal');

CREATE USER IF NOT EXISTS 'sec005_admin'@'%' IDENTIFIED BY 'Sec005_admin_2026';
CREATE USER IF NOT EXISTS 'sec005_user'@'%' IDENTIFIED BY 'Sec005_user_2026';

GRANT SELECT, INSERT, UPDATE ON `sec005_test`.* TO 'sec005_admin'@'%' WITH GRANT OPTION;
