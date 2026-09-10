-- phpMyAdmin SQL Dump
SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";

CREATE TABLE `demo_item` (`id` int NOT NULL, `name` varchar(32) NOT NULL);
INSERT INTO `demo_item` (`id`, `name`) VALUES (1, 'phpMyAdmin');
COMMIT;
