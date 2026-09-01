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
CREATE TABLE IF NOT EXISTS `obj007_projects` (
    `project_id` BIGINT NOT NULL,
    `department_id` BIGINT NOT NULL,
    `project_name` VARCHAR(128) NOT NULL,
    PRIMARY KEY (`project_id`, `department_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `obj007_project_members` (
    `member_id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_id` BIGINT NOT NULL,
    `department_id` BIGINT NOT NULL,
    `emp_id` BIGINT NULL,
    `role` VARCHAR(32) DEFAULT 'member',
    PRIMARY KEY (`member_id`),
    UNIQUE KEY `uk_pm_project_emp` (`project_id`, `department_id`, `emp_id`),
    CONSTRAINT `fk_pm_project` FOREIGN KEY (`project_id`, `department_id`)
        REFERENCES `obj007_projects` (`project_id`, `department_id`)
        ON DELETE NO ACTION ON UPDATE CASCADE,
    CONSTRAINT `fk_pm_emp` FOREIGN KEY (`emp_id`)
        REFERENCES `obj007_employees` (`emp_id`)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB;

-- Negative cases for actionable FK errors
CREATE TABLE IF NOT EXISTS `obj007_type_mismatch_parent` (
    `id` BIGINT NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `obj007_type_mismatch_child` (
    `parent_id` VARCHAR(32) NOT NULL,
    INDEX `idx_obj007_type_mismatch_child_parent` (`parent_id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `obj007_missing_index_parent` (
    `code` BIGINT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `obj007_orphan_parent` (
    `id` BIGINT NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS `obj007_orphan_child` (
    `parent_id` BIGINT NOT NULL,
    INDEX `idx_obj007_orphan_child_parent` (`parent_id`)
) ENGINE=InnoDB;

INSERT INTO `obj007_orphan_child` (`parent_id`) VALUES (404);

CREATE TABLE IF NOT EXISTS `obj007_partition_parent` (
    `id` BIGINT NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB
PARTITION BY HASH(`id`) PARTITIONS 2;

CREATE TABLE IF NOT EXISTS `obj007_partition_child` (
    `parent_id` BIGINT NOT NULL,
    INDEX `idx_obj007_partition_child_parent` (`parent_id`)
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
