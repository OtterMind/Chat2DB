-- MYSQL-OBJ-004: Index prefix length management
-- Test fixture: tables with VARCHAR, TEXT, and BLOB columns with prefix indexes

CREATE TABLE IF NOT EXISTS `obj004_prefix_test` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `code` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `name` VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `description` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
    `data` BLOB,
    PRIMARY KEY (`id`),
    KEY `idx_code` (`code`),
    KEY `idx_name_prefix` (`name`(20)),
    KEY `idx_desc_prefix` (`description`(50)),
    KEY `idx_data_prefix` (`data`(32)),
    KEY `idx_name_code` (`name`(10), `code`(5))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `obj004_prefix_myisam` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `title` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `body` TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
    PRIMARY KEY (`id`),
    KEY `idx_title` (`title`(30)),
    KEY `idx_body` (`body`(100)),
    KEY `idx_title_body` (`title`(100), `body`(100))
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `obj004_prefix_compact` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_name_compact` (`name`(191))
) ENGINE=InnoDB ROW_FORMAT=COMPACT DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
