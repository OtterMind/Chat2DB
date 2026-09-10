-- phpMyAdmin SQL Dump, data-only safe subset
START TRANSACTION;
CREATE TABLE `demo_item` (`id` int NOT NULL, `name` varchar(32) NOT NULL);
INSERT INTO `demo_item` (`id`, `name`) VALUES
  (1, 'phpMyAdmin'),
  (2, 'safe; value');
COMMIT;
