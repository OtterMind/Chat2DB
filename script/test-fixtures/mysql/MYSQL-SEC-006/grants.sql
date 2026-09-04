-- MYSQL-SEC-006: SHOW GRANTS source evidence for routine privileges.
-- Run after init.sql when validating direct vs inherited grant labels.
GRANT EXECUTE ON FUNCTION `sec006_test`.`sec006_double` TO 'sec006_user'@'%';
GRANT ALTER ROUTINE ON PROCEDURE `sec006_test`.`sec006_bump` TO 'sec006_user'@'%' WITH GRANT OPTION;
GRANT EXECUTE ON `sec006_test`.* TO 'sec006_user'@'%';
GRANT 'sec006_routine_role'@'%' TO 'sec006_user'@'%';

SHOW GRANTS FOR 'sec006_user'@'%';
FLUSH PRIVILEGES;
