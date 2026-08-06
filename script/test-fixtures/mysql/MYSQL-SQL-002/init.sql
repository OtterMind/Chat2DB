-- MYSQL-SQL-002: Structured EXPLAIN JSON and EXPLAIN ANALYZE
-- Test fixture: tables with indexes and data for representative queries

CREATE TABLE IF NOT EXISTS `obj002_users` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `email` VARCHAR(256),
    `dept_id` BIGINT,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_email` (`email`),
    KEY `idx_dept` (`dept_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `obj002_orders` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `amount` DECIMAL(10,2),
    `status` VARCHAR(32) DEFAULT 'pending',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB;

INSERT INTO `obj002_users` (`name`, `email`, `dept_id`) VALUES
    ('Alice', 'alice@test.com', 1),
    ('Bob', 'bob@test.com', 1),
    ('Carol', 'carol@test.com', 2),
    ('Dave', 'dave@test.com', 2),
    ('Eve', 'eve@test.com', 3);

INSERT INTO `obj002_orders` (`user_id`, `amount`, `status`) VALUES
    (1, 100.00, 'completed'),
    (1, 200.00, 'pending'),
    (2, 150.00, 'completed'),
    (3, 300.00, 'cancelled'),
    (4, 50.00, 'pending'),
    (5, 500.00, 'completed');
