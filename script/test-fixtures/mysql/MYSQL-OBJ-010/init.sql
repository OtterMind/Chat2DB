-- MYSQL-OBJ-010: Create and drop views from object navigation
-- Test fixture: base table and sample views

CREATE TABLE IF NOT EXISTS `obj010_base` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `dept` VARCHAR(64),
    `salary` DECIMAL(10,2),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `obj010_base` (`name`, `dept`, `salary`) VALUES
    ('Alice', 'Engineering', 90000),
    ('Bob', 'Sales', 60000),
    ('Carol', 'Engineering', 85000);

-- Simple view
CREATE OR REPLACE VIEW `obj010_simple` AS
    SELECT id, name FROM obj010_base;

-- View with attributes
CREATE OR REPLACE VIEW `obj010_aggregate` AS
    SELECT dept, COUNT(*) AS headcount, AVG(salary) AS avg_salary
    FROM obj010_base GROUP BY dept;
