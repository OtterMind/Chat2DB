-- MYSQL-OPS-001: Grants for MySQL 8.0 test user
-- ops001_admin has PROCESS for full visibility and CONNECTION_ADMIN for other-user KILL authorization.

GRANT PROCESS ON *.* TO 'ops001_admin'@'%';
GRANT CONNECTION_ADMIN ON *.* TO 'ops001_admin'@'%';

-- MySQL 5.7 does not support CONNECTION_ADMIN; use this fallback in 5.7 fixtures:
-- GRANT SUPER ON *.* TO 'ops001_admin'@'%';
FLUSH PRIVILEGES;
