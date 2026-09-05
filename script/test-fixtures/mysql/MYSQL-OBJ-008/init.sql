-- MYSQL-OBJ-008: CHECK constraint management
-- Test fixture: table with named and server-named CHECK constraints (MySQL 8.0.16+)

CREATE TABLE IF NOT EXISTS `obj008_check_test` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `age` INT,
    `email` VARCHAR(256),
    `status` VARCHAR(32) DEFAULT 'active',
    PRIMARY KEY (`id`),
    CONSTRAINT `chk_age_positive` CHECK (`age` >= 0) ENFORCED,
    CONSTRAINT `chk_status_valid` CHECK (`status` IN ('active', 'inactive', 'banned')) ENFORCED,
    CHECK (`email` IS NOT NULL OR `name` = 'anonymous') NOT ENFORCED
) ENGINE=InnoDB;

INSERT INTO `obj008_check_test` (`name`, `age`, `email`, `status`) VALUES
    ('Alice', 30, 'alice@test.com', 'active'),
    ('Bob', 25, 'bob@test.com', 'active'),
    ('Carol', 5, 'carol@test.com', 'inactive');
