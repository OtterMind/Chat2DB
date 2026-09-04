-- MYSQL-SEC-006: Cleanup
DROP DATABASE IF EXISTS `sec006_test`;
DROP USER IF EXISTS 'sec006_admin'@'%';
DROP USER IF EXISTS 'sec006_user'@'%';
DROP ROLE IF EXISTS 'sec006_routine_role'@'%';
