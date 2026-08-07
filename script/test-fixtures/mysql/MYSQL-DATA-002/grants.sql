-- MYSQL-DATA-002: Grants for test user

GRANT SELECT, INSERT, UPDATE ON *.* TO 'chat2db_test'@'%';
FLUSH PRIVILEGES;
