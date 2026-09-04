-- MYSQL-SEC-006: Function and procedure object-level privileges
-- Test fixture: a database with a function and a procedure plus two users.

CREATE DATABASE IF NOT EXISTS `sec006_test`;
USE `sec006_test`;

DELIMITER //
CREATE FUNCTION IF NOT EXISTS `sec006_double`(n INT) RETURNS INT DETERMINISTIC
BEGIN
    RETURN n * 2;
END//
CREATE PROCEDURE IF NOT EXISTS `sec006_bump`(INOUT n INT)
BEGIN
    SET n = n + 1;
END//
DELIMITER ;

CREATE USER IF NOT EXISTS 'sec006_admin'@'%' IDENTIFIED BY 'Sec006_admin_2026';
CREATE USER IF NOT EXISTS 'sec006_user'@'%' IDENTIFIED BY 'Sec006_user_2026';
CREATE ROLE IF NOT EXISTS 'sec006_routine_role'@'%';

GRANT SELECT, EXECUTE, ALTER ROUTINE ON `sec006_test`.* TO 'sec006_admin'@'%' WITH GRANT OPTION;
GRANT SELECT ON `mysql`.* TO 'sec006_admin'@'%';
GRANT SELECT ON `sec006_test`.* TO 'sec006_user'@'%';
GRANT EXECUTE ON PROCEDURE `sec006_test`.`sec006_bump` TO 'sec006_routine_role'@'%';
