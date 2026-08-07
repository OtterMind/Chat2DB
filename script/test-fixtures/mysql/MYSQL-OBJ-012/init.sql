-- MYSQL-OBJ-012: Trigger create, modify, and delete with recovery
-- Test fixture: all six timing/event combinations

CREATE TABLE IF NOT EXISTS `obj012_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `action` VARCHAR(32) NOT NULL,
    `old_value` TEXT,
    `new_value` TEXT,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `obj012_data` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64),
    `value` INT DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

DELIMITER //

-- BEFORE INSERT
CREATE TRIGGER `obj012_bi` BEFORE INSERT ON `obj012_data`
FOR EACH ROW
BEGIN
    SET NEW.value = COALESCE(NEW.value, 0) + 1;
END //

-- AFTER INSERT
CREATE TRIGGER `obj012_ai` AFTER INSERT ON `obj012_data`
FOR EACH ROW
BEGIN
    INSERT INTO `obj012_audit` (`action`, `new_value`) VALUES ('INSERT', CONCAT('id=', NEW.id, ' name=', NEW.name));
END //

-- BEFORE UPDATE
CREATE TRIGGER `obj012_bu` BEFORE UPDATE ON `obj012_data`
FOR EACH ROW
BEGIN
    SET NEW.name = UPPER(NEW.name);
END //

-- AFTER UPDATE
CREATE TRIGGER `obj012_au` AFTER UPDATE ON `obj012_data`
FOR EACH ROW
BEGIN
    INSERT INTO `obj012_audit` (`action`, `old_value`, `new_value`) VALUES ('UPDATE', CONCAT(OLD.name), CONCAT(NEW.name));
END //

-- BEFORE DELETE
CREATE TRIGGER `obj012_bd` BEFORE DELETE ON `obj012_data`
FOR EACH ROW
BEGIN
    INSERT INTO `obj012_audit` (`action`, `old_value`) VALUES ('DELETE', CONCAT('id=', OLD.id, ' name=', OLD.name));
END //

-- AFTER DELETE
CREATE TRIGGER `obj012_ad` AFTER DELETE ON `obj012_data`
FOR EACH ROW
BEGIN
    -- Nothing for now, placeholder
END //

DELIMITER ;
