-- init.sql
-- Schema for the MySQL manual-transaction integration tests (#2586).
-- Loaded by Testcontainers withInitScript as the root user before tests run.

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
