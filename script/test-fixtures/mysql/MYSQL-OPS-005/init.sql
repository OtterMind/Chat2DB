-- MYSQL-OPS-005: Table maintenance operations
-- Test fixture: InnoDB and MyISAM tables with representative data

CREATE TABLE IF NOT EXISTS `ops005_innodb` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `value` INT DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_name` (`name`)
) ENGINE=InnoDB;

INSERT INTO `ops005_innodb` (`name`, `value`) VALUES
    ('alpha', 100),
    ('beta', 200),
    ('gamma', 300);

CREATE TABLE IF NOT EXISTS `ops005_myisam` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `value` INT DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_name` (`name`)
) ENGINE=MyISAM;

INSERT INTO `ops005_myisam` (`name`, `value`) VALUES
    ('delta', 400),
    ('epsilon', 500);
