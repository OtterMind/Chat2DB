-- MYSQL-OPS-003: Data locks, metadata locks, and blocking chains
-- Test fixture: two users and a table used to construct row-lock wait chains.

CREATE DATABASE IF NOT EXISTS `ops003_test`;
USE `ops003_test`;

CREATE TABLE IF NOT EXISTS `ops003_ledger` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `account` VARCHAR(32) NOT NULL,
    `amount` DECIMAL(12,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_account` (`account`)
) ENGINE=InnoDB;

INSERT INTO `ops003_ledger` (`account`, `amount`) VALUES
    ('alice', 100.00),
    ('bob', 200.00),
    ('carol', 300.00),
    ('dave', 400.00);

CREATE USER IF NOT EXISTS 'ops003_admin'@'%' IDENTIFIED BY 'Ops003_admin_2026';
CREATE USER IF NOT EXISTS 'ops003_user'@'%' IDENTIFIED BY 'Ops003_user_2026';

GRANT SELECT, INSERT, UPDATE, DELETE ON `ops003_test`.* TO 'ops003_admin'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `ops003_test`.* TO 'ops003_user'@'%';
