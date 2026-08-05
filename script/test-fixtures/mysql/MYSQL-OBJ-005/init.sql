-- MYSQL-OBJ-005: Functional index management
-- Test fixture: tables with functional (expression-based) indexes (MySQL 8.0.13+)

CREATE TABLE IF NOT EXISTS `obj005_func_test` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `email` VARCHAR(256),
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `obj005_func_test` (`name`, `email`) VALUES
    ('Alice', 'Alice@Example.com'),
    ('Bob', 'bob@test.com'),
    ('Carol', 'CAROL@Demo.org');

-- Functional index on LOWER(name)
CREATE INDEX `idx_lower_name` ON `obj005_func_test` ((LOWER(`name`)));

-- Functional index on LOWER(email)
CREATE INDEX `idx_lower_email` ON `obj005_func_test` ((LOWER(`email`)));

-- Mixed index: expression + physical column
CREATE INDEX `idx_mixed` ON `obj005_func_test` ((LOWER(`name`)), `email`);

-- Functional index on YEAR(created_at)
CREATE INDEX `idx_year_created` ON `obj005_func_test` ((YEAR(`created_at`)));
