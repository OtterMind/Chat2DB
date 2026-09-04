-- init.sql
-- Schema for MySQL manual-transaction Web verification (#2586).
-- Load as an administrator before manual Web/Playwright verification.

CREATE DATABASE IF NOT EXISTS c2d_tx_test
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE c2d_tx_test;

-- Transactional baseline table (InnoDB): rollback protects data here.
CREATE TABLE tx_innodb (
    id   BIGINT NOT NULL AUTO_INCREMENT,
    val  VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- Non-transactional table (MyISAM): rollback does NOT protect data here. Used to assert the
-- documented compatibility boundary.
CREATE TABLE tx_myisam (
    id   BIGINT NOT NULL AUTO_INCREMENT,
    val  VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = MyISAM;
