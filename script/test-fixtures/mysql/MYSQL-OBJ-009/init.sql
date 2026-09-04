-- MYSQL-OBJ-009: Partition inspection and maintenance
-- Test fixture: all MySQL partition methods supported by MYSQL-OBJ-009.

CREATE DATABASE IF NOT EXISTS `obj009_test`;
USE `obj009_test`;

-- RANGE partitioning (DROP/TRUNCATE supported).
CREATE TABLE IF NOT EXISTS `obj009_sales_range` (
    `id` BIGINT NOT NULL,
    `sale_date` DATE NOT NULL,
    `amount` DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (`id`, `sale_date`)
) ENGINE=InnoDB
PARTITION BY RANGE (YEAR(sale_date)) (
    PARTITION p2023 VALUES LESS THAN (2024),
    PARTITION p2024 VALUES LESS THAN (2025),
    PARTITION p2025 VALUES LESS THAN (2026),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);

INSERT INTO `obj009_sales_range` VALUES
    (1, '2023-06-01', 100.00),
    (2, '2024-06-01', 200.00),
    (3, '2025-06-01', 300.00);

-- RANGE COLUMNS partitioning (ADD/REORGANIZE/DROP/TRUNCATE supported).
CREATE TABLE IF NOT EXISTS `obj009_sales_range_columns` (
    `id` BIGINT NOT NULL,
    `store_id` INT NOT NULL,
    `sale_date` DATE NOT NULL,
    PRIMARY KEY (`id`, `store_id`, `sale_date`)
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(store_id, sale_date) (
    PARTITION p_low VALUES LESS THAN (10, '2025-01-01'),
    PARTITION p_mid VALUES LESS THAN (20, '2026-01-01'),
    PARTITION p_high VALUES LESS THAN (MAXVALUE, MAXVALUE)
);

-- LIST partitioning (ADD/REORGANIZE/DROP/TRUNCATE supported).
CREATE TABLE IF NOT EXISTS `obj009_regions_list_int` (
    `id` BIGINT NOT NULL,
    `region_id` INT NOT NULL,
    PRIMARY KEY (`id`, `region_id`)
) ENGINE=InnoDB
PARTITION BY LIST (region_id) (
    PARTITION p_one VALUES IN (1, 2),
    PARTITION p_two VALUES IN (3, 4)
);

-- LIST COLUMNS partitioning (ADD/REORGANIZE/DROP/TRUNCATE supported).
CREATE TABLE IF NOT EXISTS `obj009_regions_list` (
    `id` BIGINT NOT NULL,
    `region` VARCHAR(16) NOT NULL,
    PRIMARY KEY (`id`, `region`)
) ENGINE=InnoDB
PARTITION BY LIST COLUMNS(region) (
    PARTITION p_east VALUES IN ('EAST'),
    PARTITION p_west VALUES IN ('WEST')
);

INSERT INTO `obj009_regions_list` VALUES (1, 'EAST'), (2, 'WEST');

-- HASH partitioning (ADD PARTITION PARTITIONS n and COALESCE supported).
CREATE TABLE IF NOT EXISTS `obj009_events_hash` (
    `id` BIGINT NOT NULL,
    `payload` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB
PARTITION BY HASH(id) PARTITIONS 4;

INSERT INTO `obj009_events_hash` VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd');

-- LINEAR HASH partitioning (ADD PARTITION PARTITIONS n and COALESCE supported).
CREATE TABLE IF NOT EXISTS `obj009_events_linear_hash` (
    `id` BIGINT NOT NULL,
    `payload` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB
PARTITION BY LINEAR HASH(id) PARTITIONS 4;

-- KEY partitioning (ADD PARTITION PARTITIONS n and COALESCE supported).
CREATE TABLE IF NOT EXISTS `obj009_events_key` (
    `id` BIGINT NOT NULL,
    `payload` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB
PARTITION BY KEY(id) PARTITIONS 4;

-- LINEAR KEY partitioning (ADD PARTITION PARTITIONS n and COALESCE supported).
CREATE TABLE IF NOT EXISTS `obj009_events_linear_key` (
    `id` BIGINT NOT NULL,
    `payload` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB
PARTITION BY LINEAR KEY(id) PARTITIONS 4;

-- Non-partitioned table for empty-state verification.
CREATE TABLE IF NOT EXISTS `obj009_plain_table` (
    `id` BIGINT NOT NULL PRIMARY KEY,
    `payload` VARCHAR(64) NOT NULL
) ENGINE=InnoDB;

-- Conflict examples for actionable MySQL errors. Keep commented so fixture setup succeeds.
-- Unique key conflict: UNIQUE KEY uq_region (region) omits the LIST COLUMNS partition column requirements.
-- ALTER TABLE `obj009_regions_list` ADD UNIQUE KEY uq_region_id_only (`id`);
-- Foreign-key conflict: user-partitioned InnoDB tables cannot use foreign keys.
-- ALTER TABLE `obj009_sales_range` ADD CONSTRAINT fk_obj009_plain
--     FOREIGN KEY (`id`) REFERENCES `obj009_plain_table` (`id`);

CREATE USER IF NOT EXISTS 'obj009_admin'@'%' IDENTIFIED BY 'Obj009_admin_2026';
GRANT ALL PRIVILEGES ON `obj009_test`.* TO 'obj009_admin'@'%';
