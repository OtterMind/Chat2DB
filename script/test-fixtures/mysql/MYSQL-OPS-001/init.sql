-- MYSQL-OPS-001: Session inspection and termination
-- Test fixture: test users and workload setup

-- Administrator account (for full process list visibility)
CREATE USER IF NOT EXISTS 'ops001_admin'@'%' IDENTIFIED BY 'AdminPass123!';
GRANT PROCESS, CONNECTION_ADMIN ON *.* TO 'ops001_admin'@'%';

-- Limited account (can only see own sessions)
CREATE USER IF NOT EXISTS 'ops001_user'@'%' IDENTIFIED BY 'UserPass123!';
GRANT SELECT ON *.* TO 'ops001_user'@'%';

-- Test database for workload
CREATE DATABASE IF NOT EXISTS `ops001_test`;
USE `ops001_test`;

CREATE TABLE IF NOT EXISTS `ops001_slow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `data` TEXT,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `ops001_slow` (`data`) VALUES
    ('row1'), ('row2'), ('row3');

-- Note: To test session killing, open a second connection
-- running a long query like:
--   SELECT SLEEP(60);
-- Then use the session view to find and kill it.
