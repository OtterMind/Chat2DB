-- MYSQL-SQL-002: Grants for test user

GRANT SELECT ON *.* TO 'chat2db_test'@'%';
FLUSH PRIVILEGES;
