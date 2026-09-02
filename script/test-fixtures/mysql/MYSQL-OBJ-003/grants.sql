-- MYSQL-OBJ-003: Grants for test user
-- Minimal privileges for column visibility testing

GRANT SELECT, INSERT, UPDATE, ALTER ON *.* TO 'chat2db_test'@'%';
FLUSH PRIVILEGES;
