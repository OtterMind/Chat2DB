-- MYSQL-OPS-004: Variables and status management
-- Test fixture: two users — one with SYSTEM_VARIABLES_ADMIN/SUPER (can SET GLOBAL),
-- one without (read-only for global edits). Creates a small database for session work.

CREATE DATABASE IF NOT EXISTS `ops004_test`;

CREATE USER IF NOT EXISTS 'ops004_admin'@'%' IDENTIFIED BY 'Ops004_admin_2026';
CREATE USER IF NOT EXISTS 'ops004_user'@'%' IDENTIFIED BY 'Ops004_user_2026';

GRANT SELECT ON `ops004_test`.* TO 'ops004_admin'@'%';
GRANT SELECT ON `ops004_test`.* TO 'ops004_user'@'%';
