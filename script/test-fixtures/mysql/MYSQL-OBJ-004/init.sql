-- MYSQL-OBJ-004: Index prefix length management
-- Test fixture: tables with VARCHAR, TEXT, and BLOB columns with prefix indexes

CREATE TABLE IF NOT EXISTS `obj004_prefix_test` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(32) NOT NULL,
    `name` VARCHAR(256) NOT NULL,
    `description` TEXT,
    `data` BLOB,
    PRIMARY KEY (`id`),
    KEY `idx_code` (`code`),
    KEY `idx_name_prefix` (`name`(20)),
    KEY `idx_desc_prefix` (`description`(50)),
    KEY `idx_name_code` (`name`(10), `code`(5))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `obj004_prefix_myisam` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `title` VARCHAR(255) NOT NULL,
    `body` TEXT,
    PRIMARY KEY (`id`),
    KEY `idx_title` (`title`(30)),
    KEY `idx_body` (`body`(100))
) ENGINE=MyISAM;
