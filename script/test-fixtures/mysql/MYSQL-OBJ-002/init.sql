-- MYSQL-OBJ-002: Generated column management
-- Test fixture: table with VIRTUAL and STORED generated columns plus an admin user.

CREATE DATABASE IF NOT EXISTS `obj002_test`;
USE `obj002_test`;

CREATE TABLE IF NOT EXISTS `obj002_products` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `price` DECIMAL(10,2) NOT NULL,
    `tax_rate` DECIMAL(5,2) NOT NULL DEFAULT 0.10,
    `price_with_tax` DECIMAL(12,2) GENERATED ALWAYS AS (price * (1 + tax_rate)) STORED,
    `name_upper` VARCHAR(64) GENERATED ALWAYS AS (UPPER(name)) VIRTUAL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `obj002_products` (`name`, `price`, `tax_rate`) VALUES
    ('widget', 100.00, 0.10),
    ('gadget', 50.00, 0.20);

CREATE USER IF NOT EXISTS 'obj002_admin'@'%' IDENTIFIED BY 'Obj002_admin_2026';
GRANT ALL PRIVILEGES ON `obj002_test`.* TO 'obj002_admin'@'%';
