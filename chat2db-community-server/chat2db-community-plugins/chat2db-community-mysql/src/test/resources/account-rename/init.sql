CREATE DATABASE IF NOT EXISTS chat2db_account_rename_fixture;

USE chat2db_account_rename_fixture;

DROP EVENT IF EXISTS ev_account_rename_source;
DROP TRIGGER IF EXISTS tr_account_rename_source_bi;
DROP PROCEDURE IF EXISTS pr_account_rename_source;
DROP FUNCTION IF EXISTS fn_account_rename_source;
DROP VIEW IF EXISTS v_account_rename_source;
DROP TABLE IF EXISTS account_rename_source;

CREATE TABLE account_rename_source (
  id INT PRIMARY KEY,
  name VARCHAR(64) NOT NULL
);

INSERT INTO account_rename_source (id, name)
VALUES (1, 'source')
ON DUPLICATE KEY UPDATE name = VALUES(name);

CREATE DEFINER = 'chat2db_rename_old'@'127.0.0.1'
VIEW v_account_rename_source AS
SELECT id, name FROM account_rename_source;

DELIMITER //

CREATE DEFINER = 'chat2db_rename_old'@'127.0.0.1'
FUNCTION fn_account_rename_source()
RETURNS INT
DETERMINISTIC
READS SQL DATA
BEGIN
  RETURN (SELECT COUNT(*) FROM account_rename_source);
END//

CREATE DEFINER = 'chat2db_rename_old'@'127.0.0.1'
PROCEDURE pr_account_rename_source()
BEGIN
  SELECT COUNT(*) AS total FROM account_rename_source;
END//

CREATE DEFINER = 'chat2db_rename_old'@'127.0.0.1'
TRIGGER tr_account_rename_source_bi
BEFORE INSERT ON account_rename_source
FOR EACH ROW
BEGIN
  SET NEW.name = COALESCE(NEW.name, 'source');
END//

CREATE DEFINER = 'chat2db_rename_old'@'127.0.0.1'
EVENT ev_account_rename_source
ON SCHEDULE EVERY 1 DAY
DO
BEGIN
  UPDATE account_rename_source SET name = name WHERE id = 1;
END//

DELIMITER ;
