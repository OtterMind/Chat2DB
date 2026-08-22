-- MYSQL-OBJ-005: Grants for test user

GRANT SELECT, INDEX, ALTER ON *.* TO 'chat2db_test'@'%';
FLUSH PRIVILEGES;
