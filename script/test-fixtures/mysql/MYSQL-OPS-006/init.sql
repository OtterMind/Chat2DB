-- MYSQL-OPS-006: InnoDB status and latest deadlock diagnostics
-- Test fixture: InnoDB tables to ensure status output is available

-- Administrator account
CREATE USER IF NOT EXISTS 'ops006_admin'@'%' IDENTIFIED BY 'AdminPass123!';
GRANT PROCESS ON *.* TO 'ops006_admin'@'%';

-- Test InnoDB table
CREATE TABLE IF NOT EXISTS `ops006_test` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64),
    PRIMARY KEY (`id`)
) ENGINE=InnoDB;

INSERT INTO `ops006_test` (`name`) VALUES ('alpha'), ('beta'), ('gamma');

-- Note: To produce a deadlock, open two connections and run:
-- Connection 1: BEGIN; SELECT * FROM ops006_test WHERE id=1 FOR UPDATE;
-- Connection 2: BEGIN; SELECT * FROM ops006_test WHERE id=2 FOR UPDATE;
-- Connection 1: SELECT * FROM ops006_test WHERE id=2 FOR UPDATE;  -- waits
-- Connection 2: SELECT * FROM ops006_test WHERE id=1 FOR UPDATE;  -- deadlock
-- Then SHOW ENGINE INNODB STATUS will show the LATEST DETECTED DEADLOCK section.
