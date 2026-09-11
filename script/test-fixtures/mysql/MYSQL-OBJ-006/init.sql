-- MYSQL-OBJ-006: Visible and invisible index management
-- Test fixture: table with ordinary, unique, composite, and primary indexes

CREATE TABLE IF NOT EXISTS `obj006_index_test` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(32) NOT NULL,
    `name` VARCHAR(128) NOT NULL,
    `value` INT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_name` (`name`),
    KEY `idx_name_value` (`name`, `value`)
) ENGINE=InnoDB;

-- For MySQL 8.0+ : make idx_name invisible
ALTER TABLE `obj006_index_test` ALTER INDEX `idx_name` INVISIBLE;
