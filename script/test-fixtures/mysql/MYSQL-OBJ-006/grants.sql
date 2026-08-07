-- MYSQL-OBJ-006: Grants for test user
-- Minimal privileges for index visibility testing

GRANT SELECT, INDEX, ALTER ON *.* TO 'chat2db_test'@'%';
FLUSH PRIVILEGES;
