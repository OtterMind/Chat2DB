-- MYSQL-SEC-001: Rename MySQL accounts and change Host
-- Test fixture: source and target accounts with Definer objects

-- Administrator account
CREATE USER IF NOT EXISTS 'sec001_admin'@'%' IDENTIFIED BY 'AdminPass123!';
GRANT CREATE USER, SYSTEM_USER ON *.* TO 'sec001_admin'@'%';

-- Source account to be renamed
CREATE USER IF NOT EXISTS 'sec001_source'@'localhost' IDENTIFIED BY 'SourcePass123!';
GRANT SELECT ON *.* TO 'sec001_source'@'localhost';

-- Target account (conflict test)
CREATE USER IF NOT EXISTS 'sec001_target'@'localhost' IDENTIFIED BY 'TargetPass123!';

-- Base table
CREATE TABLE IF NOT EXISTS `sec001_data` (
    `id` INT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

-- View with Definer
CREATE OR REPLACE VIEW `sec001_view` AS
    SQL SECURITY DEFINER
    SELECT * FROM `sec001_data`;

-- Function with Definer
DELIMITER //
CREATE FUNCTION `sec001_func`() RETURNS INT
DETERMINISTIC
BEGIN
    RETURN 1;
END //

-- Procedure with Definer
CREATE PROCEDURE `sec001_proc`(OUT result INT)
BEGIN
    SET result = 42;
END //

-- Trigger with Definer
CREATE TRIGGER `sec001_trig` BEFORE INSERT ON `sec001_data`
FOR EACH ROW
BEGIN
    SET NEW.id = NULL;
END //

DELIMITER ;

FLUSH PRIVILEGES;
