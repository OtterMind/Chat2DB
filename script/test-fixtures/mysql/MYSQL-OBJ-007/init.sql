-- MYSQL-OBJ-007: Foreign key management
-- Test fixture: tables with single-column, composite, and self-referencing FKs

-- Parent table
CREATE TABLE IF NOT EXISTS `obj007_departments` (
    `dept_id` BIGINT NOT NULL AUTO_INCREMENT,
    `dept_name` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`dept_id`)
) ENGINE=InnoDB;

INSERT INTO `obj007_departments` (`dept_name`) VALUES
    ('Engineering'), ('Sales'), ('HR');

-- Child table with single-column FK (CASCADE)
CREATE TABLE IF NOT EXISTS `obj007_employees` (
    `emp_id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(128) NOT NULL,
    `dept_id` BIGINT,
    PRIMARY KEY (`emp_id`),
    CONSTRAINT `fk_emp_dept` FOREIGN KEY (`dept_id`)
        REFERENCES `obj007_departments` (`dept_id`)
        ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB;

-- Child table with composite FK
CREATE TABLE IF NOT EXISTS `obj007_project_members` (
    `project_id` BIGINT NOT NULL,
    `emp_id` BIGINT NOT NULL,
    `role` VARCHAR(32) DEFAULT 'member',
    PRIMARY KEY (`project_id`, `emp_id`),
    CONSTRAINT `fk_pm_emp` FOREIGN KEY (`emp_id`)
        REFERENCES `obj007_employees` (`emp_id`)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB;

-- Self-referencing table
CREATE TABLE IF NOT EXISTS `obj007_categories` (
    `cat_id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `parent_id` BIGINT,
    PRIMARY KEY (`cat_id`),
    CONSTRAINT `fk_cat_parent` FOREIGN KEY (`parent_id`)
        REFERENCES `obj007_categories` (`cat_id`)
        ON DELETE SET NULL ON UPDATE NO ACTION
) ENGINE=InnoDB;
