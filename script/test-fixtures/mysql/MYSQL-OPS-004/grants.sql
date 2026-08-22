-- MYSQL-OPS-004: Grants for test user
-- MySQL 8.0 uses SYSTEM_VARIABLES_ADMIN for SET GLOBAL/PERSIST; 5.7 uses SUPER.
GRANT SYSTEM_VARIABLES_ADMIN ON *.* TO 'ops004_admin'@'%' ON MySQL 8.0;
GRANT SUPER ON *.* TO 'ops004_admin'@'%' ON MySQL 5.7;
-- ops004_user has no variable privileges: global edits must fail with the server error.
FLUSH PRIVILEGES;
