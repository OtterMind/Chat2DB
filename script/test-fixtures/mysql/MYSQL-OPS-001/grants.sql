-- MYSQL-OPS-001: Grants for test user
-- ops001_admin has PROCESS and CONNECTION_ADMIN for full visibility

GRANT PROCESS ON *.* TO 'ops001_admin'@'%';
GRANT CONNECTION_ADMIN ON *.* TO 'ops001_admin'@'%' ON MySQL 8.0;
-- MySQL 5.7 uses SUPER instead
GRANT SUPER ON *.* TO 'ops001_admin'@'%' ON MySQL 5.7;
FLUSH PRIVILEGES;
