-- MYSQL-SQL-002: Cleanup

DROP TABLE IF EXISTS `obj002_orders`;
DROP TABLE IF EXISTS `obj002_users`;
DROP USER IF EXISTS 'chat2db_explain_reader'@'%';
DROP USER IF EXISTS 'chat2db_explain_limited'@'%';
