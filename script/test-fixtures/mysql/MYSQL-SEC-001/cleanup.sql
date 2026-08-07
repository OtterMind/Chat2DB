-- MYSQL-SEC-001: Cleanup

DROP TRIGGER IF EXISTS `sec001_trig`;
DROP PROCEDURE IF EXISTS `sec001_proc`;
DROP FUNCTION IF EXISTS `sec001_func`;
DROP VIEW IF EXISTS `sec001_view`;
DROP TABLE IF EXISTS `sec001_data`;
DROP USER IF EXISTS 'sec001_source'@'localhost';
DROP USER IF EXISTS 'sec001_target'@'localhost';
DROP USER IF EXISTS 'sec001_renamed'@'%';
DROP USER IF EXISTS 'sec001_admin'@'%';
