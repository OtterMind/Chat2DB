-- MYSQL-OPS-002: Active transaction inspection
-- Test fixture: users, a test table, and a stored helper that opens transactions.
-- The admin account can see all transactions (PROCESS). The limited account has no
-- PROCESS grant: MySQL may hide cross-user transaction/process details, return NULL SQL
-- text, or reject active-transaction inspection depending on the server/version path.

CREATE DATABASE IF NOT EXISTS `ops002_test`;
USE `ops002_test`;

CREATE TABLE IF NOT EXISTS `ops002_accounts` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `balance` DECIMAL(12,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `ops002_accounts` (`name`, `balance`) VALUES
    ('alice', 1000.00),
    ('bob', 500.00),
    ('carol', 250.00);

-- Two test users: full visibility vs limited visibility.
CREATE USER IF NOT EXISTS 'ops002_admin'@'%' IDENTIFIED BY 'Ops002_admin_2026';
CREATE USER IF NOT EXISTS 'ops002_user'@'%' IDENTIFIED BY 'Ops002_user_2026';

GRANT SELECT, INSERT, UPDATE, DELETE ON `ops002_test`.* TO 'ops002_admin'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `ops002_test`.* TO 'ops002_user'@'%';
