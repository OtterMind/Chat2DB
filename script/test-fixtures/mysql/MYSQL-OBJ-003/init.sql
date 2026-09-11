-- MYSQL-OBJ-003: Visible and invisible column management
-- Test fixture: table with visible and invisible columns

CREATE TABLE IF NOT EXISTS `obj003_column_test` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `secret` VARCHAR(256) DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

-- For MySQL 8.0.23+: make the secret column invisible
ALTER TABLE `obj003_column_test` MODIFY COLUMN `secret` VARCHAR(256) DEFAULT NULL INVISIBLE;
