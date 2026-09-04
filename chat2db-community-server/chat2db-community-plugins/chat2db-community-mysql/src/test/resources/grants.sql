-- grants.sql
-- Separate fixture accounts for MySQL manual-transaction Web verification (#2586).
-- Run as root before manual Web/Playwright verification.
--
--   c2d_tx_admin : full DDL + DML on c2d_tx_test (used for setup/teardown and visibility checks).
--   c2d_tx_dml   : DML-only on c2d_tx_test (used to verify isolation from a second connection).

CREATE USER IF NOT EXISTS 'c2d_tx_admin'@'%' IDENTIFIED BY 'C2D_tx_admin_2026';
GRANT ALL PRIVILEGES ON c2d_tx_test.* TO 'c2d_tx_admin'@'%';

CREATE USER IF NOT EXISTS 'c2d_tx_dml'@'%' IDENTIFIED BY 'C2D_tx_dml_2026';
-- DML only: SELECT/INSERT/UPDATE/DELETE, no DDL, no GRANT OPTION.
GRANT SELECT, INSERT, UPDATE, DELETE ON c2d_tx_test.* TO 'c2d_tx_dml'@'%';

FLUSH PRIVILEGES;
