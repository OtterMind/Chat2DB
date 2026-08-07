-- MYSQL-OBJ-013: Event lifecycle management
-- Test fixture: a database with one-time and recurring events plus an admin user.

CREATE DATABASE IF NOT EXISTS `obj013_test`;
USE `obj013_test`;

CREATE TABLE IF NOT EXISTS `obj013_jobs` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `ran_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

-- Recurring event (disabled by default so tests are deterministic).
CREATE EVENT IF NOT EXISTS `obj013_cleanup_event`
ON SCHEDULE EVERY 1 DAY
STARTS CURRENT_TIMESTAMP
ON COMPLETION PRESERVE
DISABLE
DO
DELETE FROM `obj013_jobs` WHERE `ran_at` < NOW() - INTERVAL 30 DAY;

-- One-time event in the future.
CREATE EVENT IF NOT EXISTS `obj013_one_shot`
ON SCHEDULE AT CURRENT_TIMESTAMP + INTERVAL 1 DAY
ON COMPLETION NOT PRESERVE
ENABLE
DO
INSERT INTO `obj013_jobs` (`ran_at`) VALUES (NOW());

CREATE USER IF NOT EXISTS 'obj013_admin'@'%' IDENTIFIED BY 'Obj013_admin_2026';
GRANT ALL PRIVILEGES ON `obj013_test`.* TO 'obj013_admin'@'%';
