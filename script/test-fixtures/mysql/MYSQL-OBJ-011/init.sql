-- MYSQL-OBJ-011: Create and drop functions and procedures from object navigation
-- Test fixture: same-named function and procedure, all parameter modes

-- Base table
CREATE TABLE IF NOT EXISTS `obj011_data` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `value` INT DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `obj011_data` (`value`) VALUES (10), (20), (30);

-- Same-named function and procedure
DELIMITER //
CREATE FUNCTION `obj011_add`(a INT, b INT) RETURNS INT
DETERMINISTIC
BEGIN
    RETURN a + b;
END //

CREATE PROCEDURE `obj011_add`(IN a INT, IN b INT, OUT result INT)
BEGIN
    SET result = a + b;
END //

-- Function with all parameter modes
CREATE FUNCTION `obj011_compute`(x INT) RETURNS INT
DETERMINISTIC
BEGIN
    RETURN x * 2;
END //

-- Procedure with IN, OUT, INOUT
CREATE PROCEDURE `obj011_swap`(INOUT a INT, INOUT b INT)
BEGIN
    DECLARE temp INT;
    SET temp = a;
    SET a = b;
    SET b = temp;
END //

DELIMITER ;
