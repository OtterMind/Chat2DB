-- MYSQL-OPS-005: Grants for test user

GRANT SELECT, INSERT, ALTER, INDEX, ANALYZE, OPTIMIZE, CHECK ON *.* TO 'chat2db_test'@'%';
-- REPAIR TABLE requires additional privileges for MyISAM tables
GRANT SELECT, INSERT, ALTER, INDEX, REPAIR ON *.* TO 'chat2db_test'@'%';
FLUSH PRIVILEGES;
