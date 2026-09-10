-- HeidiSQL export
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8mb4 */;

CREATE TABLE `app`.`demo_item` (
  `id` bigint NOT NULL,
  `note` varchar(64) DEFAULT NULL
);
LOCK TABLES `app`.`demo_item` WRITE;
INSERT INTO `app`.`demo_item` (`id`, `note`) VALUES
  (1, 'HeidiSQL; value'),
  (2, 'two');
UNLOCK TABLES;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
