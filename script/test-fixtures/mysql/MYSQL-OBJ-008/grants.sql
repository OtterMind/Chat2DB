-- MYSQL-OBJ-008: Grants for test user

GRANT SELECT, INSERT, UPDATE, ALTER ON *.* TO 'chat2db_test'@'%';
FLUSH PRIVILEGES;
