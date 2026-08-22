-- cleanup.sql
-- Tears down the MySQL manual-transaction test schema and accounts (#2586).
-- Run as root after the test suite completes.

DROP DATABASE IF EXISTS c2d_tx_test;
DROP USER IF EXISTS 'c2d_tx_admin'@'%';
DROP USER IF EXISTS 'c2d_tx_dml'@'%';
FLUSH PRIVILEGES;
