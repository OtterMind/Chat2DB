/*
 Navicat Premium Data Transfer
 Source Server Type : MySQL
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `demo_item`;
CREATE TABLE `demo_item` (
  `id` bigint NOT NULL,
  `note` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
);

LOCK TABLES `demo_item` WRITE;
/*!40000 ALTER TABLE `demo_item` DISABLE KEYS */;
INSERT INTO `demo_item` VALUES
  (1, 'semi;colon -- # /* literal */'),
  (2, 'O''Brien');
REPLACE INTO `demo_item` VALUES (3, "double;quote");

DELIMITER $$
CREATE PROCEDURE `seed_internal`()
BEGIN
  INSERT INTO `demo_item` VALUES (99, 'must-not-escape-procedure');
  SET @label = 'inner;value';
END$$
DELIMITER ;

INSERT IGNORE INTO `demo_item` VALUES (4, 'after delimiter');
UPDATE `demo_item` SET `note` = 'updated;value' WHERE `id` = 4;
DELETE FROM `demo_item` WHERE `id` = 999;

UNLOCK TABLES;
COMMIT;
SET FOREIGN_KEY_CHECKS = 1;
