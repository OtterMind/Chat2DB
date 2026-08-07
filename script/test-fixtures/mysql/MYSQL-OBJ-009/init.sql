-- MYSQL-OBJ-009: Partition inspection and maintenance
-- Test fixture: RANGE-partitioned, LIST-partitioned, and HASH-partitioned tables.

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

-- LIST partitioning (DROP/TRUNCATE supported).
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

-- HASH partitioning (COALESCE supported).
CREATE TABLE IF NOT EXISTS `obj009_events_hash` (
    `id` BIGINT NOT NULL,
    `payload` VARCHAR(64) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB
PARTITION BY HASH(id) PARTITIONS 4;

INSERT INTO `obj009_events_hash` VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd');

CREATE USER IF NOT EXISTS 'obj009_admin'@'%' IDENTIFIED BY 'Obj009_admin_2026';
GRANT ALL PRIVILEGES ON `obj009_test`.* TO 'obj009_admin'@'%';
